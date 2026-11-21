package com.jarvis.app.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jarvis.app.AppContainer
import com.jarvis.app.data.SettingsStore
import com.jarvis.app.data.TokenStore
import com.jarvis.core.model.AiEngine
import com.jarvis.core.model.ConnectionStatus
import com.jarvis.core.model.ConversationLog
import com.jarvis.core.model.ConversationMessage
import com.jarvis.core.model.JarvisFeature
import com.jarvis.core.model.JarvisTask
import com.jarvis.core.model.TaskBoard
import com.jarvis.core.network.AssistEvent
import com.jarvis.core.network.AssistService
import com.jarvis.core.network.BackendAssistService
import com.jarvis.core.network.BackendClient
import com.jarvis.core.network.BackendConfiguration
import com.jarvis.core.network.EngineStatusMapper
import com.jarvis.core.network.HistoryTurn
import com.jarvis.core.network.HttpTransport
import com.jarvis.core.network.SseTransport
import com.jarvis.core.network.UsageResponse
import com.jarvis.core.routing.Classification
import com.jarvis.core.routing.CommandClassifier
import com.jarvis.core.routing.Route
import com.jarvis.core.state.JarvisState
import com.jarvis.core.state.JarvisStateMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 마이크 상태. 마이크가 켜져 있으면 화면에 반드시 눈에 띄게 표시한다. */
enum class MicrophoneStatus(val label: String, val description: String) {
    /** PHASE 3 이전: 음성 기능 미구현 */
    UNAVAILABLE("MIC 미구현", "마이크 기능은 아직 구현되지 않았습니다"),

    /** 권한 미허용 등으로 꺼져 있음 */
    OFF("MIC OFF", "마이크 꺼짐"),

    /** 마이크 사용 중 (음성 인식 중) */
    LIVE("MIC LIVE", "마이크 사용 중"),
}

data class MainUiState(
    val machine: JarvisStateMachine = JarvisStateMachine(),
    val log: ConversationLog = ConversationLog(),
    val tasks: TaskBoard = TaskBoard(),
    val backend: ConnectionStatus = ConnectionStatus.Unknown,
    val engines: Map<AiEngine, ConnectionStatus> = AiEngine.remoteEngines.associateWith { ConnectionStatus.Unknown },
    /** 오늘의 API 사용량 (백엔드 `/v1/usage`). 연결 전에는 null. */
    val usage: UsageResponse? = null,
    /** 빠른 기능 메뉴로 지정한 다음 입력의 경로 (예: 검색). */
    val pendingRoute: Route? = null,
    /** PHASE 3에서 음성 엔진과 연결된다. */
    val microphone: MicrophoneStatus = MicrophoneStatus.UNAVAILABLE,
    /** 0..1 — PHASE 3에서 마이크/TTS 레벨과 연결된다. */
    val audioLevel: Float = 0f,
    val hasToken: Boolean = false,
) {
    val state: JarvisState get() = machine.state

    /** 화면에 표시할 작업: 진행 중 작업, 없으면 최근 종료 작업 1개. */
    val visibleTasks: List<JarvisTask>
        get() = tasks.activeTasks.ifEmpty { tasks.finishedTasks.takeLast(1) }
}

/**
 * 메인 화면 상태와 사용자 동작 처리.
 *
 * 요청 흐름: 입력 → 로컬 분류기(CommandClassifier) → 경로별 처리
 *   DEVICE: 기기 안에서 처리 (PHASE 4)
 *   CHAT / SEARCH / RESEARCH / AGENT: 백엔드 `/v1/assist` 스트림 → 상태 표시·대화 기록 실시간 갱신
 */
class MainViewModel(
    private val settings: SettingsStore,
    private val tokens: TokenStore,
    private val transport: HttpTransport,
    private val sseTransport: SseTransport,
    private val assistServiceFactory: (BackendConfiguration) -> AssistService = { config ->
        BackendAssistService(config, sseTransport, tokens)
    },
) : ViewModel() {

    private val _ui = MutableStateFlow(MainUiState(hasToken = tokens.hasToken()))
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    val backendUrl: StateFlow<String> =
        settings.backendUrl.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private var refreshJob: Job? = null
    private var assistJob: Job? = null
    private var returnToIdleJob: Job? = null
    private var activeTaskId: String? = null

    // region 백엔드 상태

    /** 백엔드 `/health`, `/v1/engines`, `/v1/usage`를 호출해 연결 상태를 갱신한다. */
    fun refreshEngineStatus(): Job {
        refreshJob?.cancel()
        return viewModelScope.launch { doRefresh() }.also { refreshJob = it }
    }

    private suspend fun doRefresh() {
        val configuration = try {
            BackendConfiguration.validated(settings.currentBackendUrl())
        } catch (_: BackendConfiguration.ValidationError.Empty) {
            _ui.update { it.copy(backend = ConnectionStatus.NotConfigured, engines = allEngines(ConnectionStatus.Unknown)) }
            return
        } catch (e: BackendConfiguration.ValidationError) {
            _ui.update { it.copy(backend = ConnectionStatus.Unreachable(e.message.orEmpty()), engines = allEngines(ConnectionStatus.Unknown)) }
            return
        }

        val client = BackendClient(configuration, transport, tokens)
        var taskId = ""
        _ui.update {
            val (board, id) = it.tasks.start("백엔드 연결 확인")
            taskId = id
            it.copy(backend = ConnectionStatus.Checking, engines = allEngines(ConnectionStatus.Checking), tasks = board)
        }

        try {
            val health = client.health()
            check(health.isOk) { "status=${health.status}" }
            _ui.update { it.copy(backend = ConnectionStatus.Connected) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message.orEmpty()
            _ui.update {
                it.copy(
                    backend = ConnectionStatus.Unreachable(reason),
                    engines = allEngines(ConnectionStatus.Unknown),
                    tasks = it.tasks.finish(taskId, JarvisTask.Status.FAILED, reason),
                )
            }
            return
        }

        try {
            val statuses = EngineStatusMapper.statuses(client.engines())
            val usage = runCatching { client.usage() }.getOrNull()
            _ui.update {
                it.copy(
                    engines = statuses,
                    usage = usage ?: it.usage,
                    tasks = it.tasks.finish(taskId, JarvisTask.Status.SUCCEEDED, "백엔드 연결됨 · 엔진 설정 확인 완료"),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message.orEmpty()
            _ui.update {
                it.copy(
                    engines = allEngines(ConnectionStatus.Unknown),
                    tasks = it.tasks.finish(taskId, JarvisTask.Status.FAILED, reason),
                    log = it.log.append(ConversationMessage(ConversationMessage.Role.SYSTEM, reason)),
                )
            }
        }
    }

    private fun refreshUsage() {
        viewModelScope.launch {
            val configuration = runCatching { BackendConfiguration.validated(settings.currentBackendUrl()) }.getOrNull() ?: return@launch
            val usage = runCatching { BackendClient(configuration, transport, tokens).usage() }.getOrNull() ?: return@launch
            _ui.update { it.copy(usage = usage) }
        }
    }

    // endregion

    // region 사용자 명령

    fun submit(input: String) {
        val text = input.trim()
        if (text.isEmpty() || _ui.value.state.isBusy) return
        returnToIdleJob?.cancel()

        val forced = _ui.value.pendingRoute
        val classification = forced?.let { Classification(it, "빠른 기능 선택") } ?: CommandClassifier.classify(text)
        val history = recentHistory()

        _ui.update {
            it.copy(pendingRoute = null, log = it.log.append(ConversationMessage(ConversationMessage.Role.USER, text)))
        }
        move(JarvisState.Analyzing)

        if (classification.route == Route.DEVICE) {
            // PHASE 4에서 로컬 명령 엔진(캘린더·알람·메모)으로 실행한다. 지금은 실행한 척하지 않는다.
            appendSystem("기기 기능 요청으로 분류했습니다. 일정·알람·메모 실행은 PHASE 4에서 구현됩니다.")
            finishWith(JarvisState.Completed)
            return
        }

        val configuration = runCatching { BackendConfiguration.validated(backendUrl.value) }.getOrElse {
            fail("백엔드 주소가 올바르지 않습니다. 설정에서 확인하세요. (${it.message})")
            return
        }

        startTask(classification.route.title, null)
        assistJob = viewModelScope.launch {
            var assistantId: String? = null
            var finished = false
            try {
                assistServiceFactory(configuration).assist(classification.route, text, history).collect { event ->
                    when (event) {
                        is AssistEvent.RouteInfo -> {
                            move(stateFor(event.engine, classification.route))
                            updateTask { it.copy(engine = event.engine, detail = "${event.engine.displayName} · ${event.model}") }
                        }
                        is AssistEvent.Notice -> appendSystem(event.text)
                        is AssistEvent.StageChange -> when (event.stage) {
                            "searching" -> updateTask { it.copy(detail = "웹 검색 중" + (event.detail?.let { d -> ": $d" } ?: "")) }
                            "responding" -> updateTask { it.copy(detail = "답변 작성 중") }
                            "reasoning" -> updateTask { it.copy(detail = "추론 중") }
                        }
                        is AssistEvent.Delta -> {
                            val id = assistantId ?: newAssistantMessage().also { assistantId = it }
                            _ui.update { s -> s.copy(log = s.log.update(id) { m -> m.copy(text = m.text + event.text) }) }
                        }
                        is AssistEvent.SourcesInfo -> {
                            val id = assistantId ?: newAssistantMessage().also { assistantId = it }
                            _ui.update { s ->
                                s.copy(log = s.log.update(id) { m ->
                                    m.copy(
                                        sources = (m.sources + event.items).distinctBy { it.url },
                                        sourceProvider = event.provider,
                                        searchSuggestionsHtml = event.searchSuggestionsHtml ?: m.searchSuggestionsHtml,
                                    )
                                })
                            }
                        }
                        is AssistEvent.Done -> {
                            finished = true
                            assistantId?.let { id -> _ui.update { s -> s.copy(log = s.log.update(id) { it.copy(isStreaming = false) }) } }
                            val tokens = listOfNotNull(event.inputTokens, event.outputTokens).sum()
                            val detail = buildString {
                                append(event.engine?.displayName ?: "")
                                if (event.cached) append(" · 캐시 응답") else if (tokens > 0) append(" · 토큰 $tokens")
                            }
                            finishTask(JarvisTask.Status.SUCCEEDED, detail)
                            finishWith(JarvisState.Completed)
                            refreshUsage()
                        }
                        is AssistEvent.Failure -> {
                            finished = true
                            fail(event.message)
                        }
                    }
                }
                if (!finished) fail("응답이 중간에 끊겼습니다. 다시 시도하세요.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: "요청을 처리하지 못했습니다.")
            } finally {
                assistantId?.let { id -> _ui.update { s -> s.copy(log = s.log.update(id) { it.copy(isStreaming = false) }) } }
            }
        }
    }

    fun selectFeature(feature: JarvisFeature) {
        if (!feature.isImplemented) {
            appendSystem("${feature.title} 기능은 ${feature.availabilityLabel}입니다.")
            return
        }
        when (feature) {
            JarvisFeature.SEARCH -> {
                _ui.update { it.copy(pendingRoute = Route.SEARCH) }
                appendSystem("검색어를 입력하세요. 다음 입력은 Google 검색으로 처리합니다.")
            }
            else -> Unit
        }
    }

    fun microphoneTapped() {
        // PHASE 3: RECORD_AUDIO 권한 요청 및 SpeechRecognizer 시작.
        appendSystem("음성 인식은 PHASE 3에서 구현됩니다.")
    }

    /** 진행 중인 처리를 중단한다 (스트림 연결을 끊어 백엔드 처리도 멈춘다). */
    fun cancelCurrent() {
        assistJob?.cancel()
        returnToIdleJob?.cancel()
        activeTaskId = null
        _ui.update { it.copy(tasks = it.tasks.cancelAll(), machine = it.machine.reset(), pendingRoute = null) }
    }

    fun clearConversation() {
        _ui.update { it.copy(log = it.log.cleared()) }
    }

    // endregion

    // region 설정

    fun setBackendUrl(url: String) {
        viewModelScope.launch { settings.setBackendUrl(url) }
    }

    fun saveToken(token: String): Result<Unit> = runCatching {
        tokens.save(token.trim())
        _ui.update { it.copy(hasToken = true) }
    }

    fun deleteToken(): Result<Unit> = runCatching {
        tokens.delete()
        _ui.update { it.copy(hasToken = false) }
    }

    // endregion

    // region 디자인 점검 (디버그 빌드의 설정 화면, 미리보기, 스냅샷 테스트 전용)

    fun debugForceState(state: JarvisState) {
        returnToIdleJob?.cancel()
        _ui.update {
            it.copy(machine = it.machine.forced(state), audioLevel = if (state == JarvisState.Listening) 0.6f else 0f)
        }
    }

    fun debugSeedConversation() {
        _ui.update {
            it.copy(
                log = it.log
                    .append(ConversationMessage(ConversationMessage.Role.USER, "자비스, 오늘 일정 알려줘."))
                    .append(
                        ConversationMessage(
                            ConversationMessage.Role.ASSISTANT,
                            "[디자인 미리보기] 실제 응답이 아닌 화면 점검용 예시 메시지입니다.",
                            AiEngine.NVIDIA,
                        ),
                    )
                    .append(ConversationMessage(ConversationMessage.Role.SYSTEM, "캘린더 접근은 PHASE 4에서 구현됩니다.")),
            )
        }
    }

    // endregion

    private fun recentHistory(): List<HistoryTurn> = _ui.value.log.messages
        .filter { it.text.isNotBlank() && it.role != ConversationMessage.Role.SYSTEM }
        .takeLast(20)
        .map { HistoryTurn(if (it.role == ConversationMessage.Role.USER) "user" else "assistant", it.text) }

    private fun newAssistantMessage(): String {
        val engine = _ui.value.state.activeEngine?.takeIf { it.isRemote }
        val message = ConversationMessage(ConversationMessage.Role.ASSISTANT, "", engine, isStreaming = true)
        _ui.update { it.copy(log = it.log.append(message)) }
        return message.id
    }

    /** 엔진·경로에 맞는 화면 상태. 에이전트(및 검색 분석 대체)는 NVIDIA 대형 모델의 "심층 추론 중". */
    private fun stateFor(engine: AiEngine, route: Route): JarvisState = when (engine) {
        AiEngine.NVIDIA -> if (route == Route.AGENT || route == Route.RESEARCH) JarvisState.Reasoning else JarvisState.NvidiaResponding
        AiEngine.GOOGLE -> JarvisState.GoogleSearching
        AiEngine.LOCAL -> JarvisState.ExecutingTask
    }

    private fun startTask(title: String, engine: AiEngine?) {
        _ui.update {
            val (board, id) = it.tasks.start(title, engine)
            activeTaskId = id
            it.copy(tasks = board)
        }
    }

    private fun updateTask(transform: (JarvisTask) -> JarvisTask) {
        val id = activeTaskId ?: return
        _ui.update { s -> s.copy(tasks = s.tasks.copy(tasks = s.tasks.tasks.map { if (it.id == id) transform(it) else it })) }
    }

    private fun finishTask(status: JarvisTask.Status, detail: String?) {
        val id = activeTaskId ?: return
        activeTaskId = null
        _ui.update { it.copy(tasks = it.tasks.finish(id, status, detail)) }
    }

    private fun fail(message: String) {
        finishTask(JarvisTask.Status.FAILED, message)
        appendSystem(message)
        move(JarvisState.Error(message))
    }

    /** 완료 상태로 바꾸고 잠시 뒤 대기 상태로 돌아간다. */
    private fun finishWith(state: JarvisState) {
        move(state)
        returnToIdleJob?.cancel()
        returnToIdleJob = viewModelScope.launch {
            delay(1_500)
            if (_ui.value.state == state) _ui.update { it.copy(machine = it.machine.reset()) }
        }
    }

    private fun move(next: JarvisState) {
        _ui.update {
            val machine = try {
                it.machine.transition(next)
            } catch (e: IllegalStateException) {
                // 잘못된 전이는 개발 중 버그 — 오류 상태로 표시해 숨기지 않는다.
                it.machine.forced(JarvisState.Error(e.message.orEmpty()))
            }
            it.copy(machine = machine)
        }
    }

    private fun appendSystem(text: String) {
        _ui.update { it.copy(log = it.log.append(ConversationMessage(ConversationMessage.Role.SYSTEM, text))) }
    }

    private fun allEngines(status: ConnectionStatus) = AiEngine.remoteEngines.associateWith { status }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(container.settings, container.tokens, container.transport, container.sse) }
        }
    }
}
