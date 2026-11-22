package com.jarvis.app.assistant

import com.jarvis.app.data.SettingsStore
import com.jarvis.app.voice.VoiceEvent
import com.jarvis.app.voice.VoiceInput
import com.jarvis.app.voice.VoiceOutput
import com.jarvis.core.voice.SpeechText
import com.jarvis.core.voice.VoiceSettings
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 마이크 상태. 마이크가 켜져 있으면 화면에 반드시 눈에 띄게 표시한다. */
enum class MicrophoneStatus(val label: String, val description: String, val isLive: Boolean) {
    /** 이 기기에 음성 인식기가 없음 */
    UNAVAILABLE("MIC 미지원", "이 기기에서 음성 인식을 사용할 수 없습니다", false),

    /** 마이크 권한 없음 */
    NO_PERMISSION("MIC 권한 필요", "마이크 권한이 필요합니다", false),

    /** 꺼져 있음 */
    OFF("MIC OFF", "마이크 꺼짐", false),

    /** "자비스" 호출 대기 중 (오프라인 호출어 감지 — 마이크 사용 중) */
    WAKE("호출 대기", "자비스 호출을 기다리는 중, 마이크 사용 중", true),

    /** 음성 인식 중 */
    LIVE("MIC LIVE", "마이크 사용 중", true),
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
    /** 0..1 — 마이크 입력 음량(인식 중) / 말하기 표시(TTS 중) */
    val audioLevel: Float = 0f,
    val hasToken: Boolean = false,
    // --- 음성 ---
    val voiceSettings: VoiceSettings = VoiceSettings(),
    val voiceAvailable: Boolean = false,
    val micPermission: Boolean = false,
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    /** 말하는 도중의 중간 인식 결과 */
    val partialTranscript: String? = null,
    /** 앱이 꺼져 있어도 "자비스"를 기다리는 서비스가 실행 중인지 */
    val wakeServiceRunning: Boolean = false,
    /** 음성 세션(듣기→처리→읽기) 진행 중 */
    val voiceSessionActive: Boolean = false,
    /** JARVIS가 휴대폰의 기본 디지털 어시스턴트로 지정되어 있는지 */
    val isDefaultAssistant: Boolean = false,
) {
    /** 시리처럼 쓰기 위한 설정(기본 어시스턴트 · 호출어 대기)이 아직 남아 있는지 */
    val needsAssistantSetup: Boolean
        get() = voiceAvailable && (!isDefaultAssistant || !voiceSettings.wakeWordEnabled)

    val state: JarvisState get() = machine.state

    val microphone: MicrophoneStatus
        get() = when {
            isListening -> MicrophoneStatus.LIVE
            wakeServiceRunning -> MicrophoneStatus.WAKE
            !voiceAvailable -> MicrophoneStatus.UNAVAILABLE
            !micPermission -> MicrophoneStatus.NO_PERMISSION
            else -> MicrophoneStatus.OFF
        }

    /** 코어 아래 표시할 상태 문구 */
    val statusLabel: String
        get() = when {
            isSpeaking -> "응답 읽는 중"
            else -> state.displayName
        }

    /** 화면에 표시할 작업: 진행 중 작업, 없으면 최근 종료 작업 1개. */
    val visibleTasks: List<JarvisTask>
        get() = tasks.activeTasks.ifEmpty { tasks.finishedTasks.takeLast(1) }
}

/**
 * JARVIS 컨트롤러 — 앱 전역(Application 범위)에서 하나만 존재한다.
 *
 * 화면(MainViewModel)과 백그라운드 호출어 서비스(JarvisVoiceService)가 같은 컨트롤러를 공유하므로,
 * 앱이 꺼져 있을 때 음성으로 나눈 대화도 앱을 열면 그대로 보인다.
 *
 * 요청 흐름: 입력(텍스트·음성) → 로컬 분류기(CommandClassifier) → 경로별 처리
 *   DEVICE: 기기 안에서 처리 (PHASE 4)
 *   CHAT / SEARCH / RESEARCH / AGENT: 백엔드 `/v1/assist` 스트림 → 상태 표시·대화 기록 실시간 갱신
 * 음성: 듣기(STT) → 위 흐름 → 답변 읽기(TTS) → (대화 모드면) 다시 듣기
 */
class JarvisController(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val tokens: TokenStore,
    private val transport: HttpTransport,
    private val sseTransport: SseTransport,
    private val voiceInput: VoiceInput,
    private val voiceOutput: VoiceOutput,
    private val assistServiceFactory: (BackendConfiguration) -> AssistService = { config ->
        BackendAssistService(config, sseTransport, tokens)
    },
) {

    private val _ui = MutableStateFlow(MainUiState(hasToken = tokens.hasToken(), voiceAvailable = voiceInput.isAvailable()))
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    val backendUrl: StateFlow<String> =
        settings.backendUrl.stateIn(scope, SharingStarted.Eagerly, "")

    private val _voiceActive = MutableStateFlow(false)

    /** 음성 세션(듣기·처리·말하기)이 진행 중인지 — 호출어 서비스는 이 동안 마이크를 놓는다. */
    val voiceActive: StateFlow<Boolean> = _voiceActive.asStateFlow()

    private var voiceJob: Job? = null

    init {
        scope.launch {
            settings.voiceSettings.collect { v -> _ui.update { it.copy(voiceSettings = v.normalized()) } }
        }
    }

    private var refreshJob: Job? = null
    private var assistJob: Job? = null
    private var returnToIdleJob: Job? = null
    private var activeTaskId: String? = null

    // region 백엔드 상태

    /** 백엔드 `/health`, `/v1/engines`, `/v1/usage`를 호출해 연결 상태를 갱신한다. */
    fun refreshEngineStatus(): Job {
        refreshJob?.cancel()
        return scope.launch { doRefresh() }.also { refreshJob = it }
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
        scope.launch {
            val configuration = runCatching { BackendConfiguration.validated(settings.currentBackendUrl()) }.getOrNull() ?: return@launch
            val usage = runCatching { BackendClient(configuration, transport, tokens).usage() }.getOrNull() ?: return@launch
            _ui.update { it.copy(usage = usage) }
        }
    }

    // endregion

    // region 사용자 명령

    /** 텍스트 입력 처리 (화면의 입력창). */
    fun submit(input: String) {
        if (input.isBlank() || _ui.value.state.isBusy) return
        assistJob = scope.launch { process(input) }
    }

    /**
     * 명령 하나를 처리하고 결과 문장(답변 또는 안내)을 돌려준다. 실패하면 null.
     * 음성 세션은 이 결과를 소리 내어 읽는다.
     */
    suspend fun process(input: String): String? {
        val text = input.trim()
        val state = _ui.value.state
        if (text.isEmpty() || (state.isBusy && state != JarvisState.Listening)) return null
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
            val notice = "기기 기능 요청으로 분류했습니다. 일정·알람·메모 실행은 PHASE 4에서 구현됩니다."
            appendSystem(notice)
            finishWith(JarvisState.Completed)
            return notice
        }

        val configuration = runCatching { BackendConfiguration.validated(settings.currentBackendUrl()) }.getOrElse {
            val message = "백엔드 주소가 올바르지 않습니다. 설정에서 확인하세요. (${it.message})"
            fail(message)
            return null
        }

        startTask(classification.route.title, null)
        var assistantId: String? = null
        var finished = false
        var answer: String? = null
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
                        answer = assistantId?.let { id -> _ui.value.log.messages.firstOrNull { it.id == id }?.text }
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
        return answer
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

    // region 음성

    fun setMicPermission(granted: Boolean) {
        _ui.update { it.copy(micPermission = granted, voiceAvailable = voiceInput.isAvailable()) }
    }

    fun setDefaultAssistant(isDefault: Boolean) {
        _ui.update { it.copy(isDefaultAssistant = isDefault) }
    }

    fun setWakeServiceRunning(running: Boolean) {
        _ui.update { it.copy(wakeServiceRunning = running) }
    }

    fun updateVoiceSettings(transform: (VoiceSettings) -> VoiceSettings) {
        val next = transform(_ui.value.voiceSettings).normalized()
        _ui.update { it.copy(voiceSettings = next) }
        scope.launch { settings.setVoiceSettings(next) }
    }

    /** 마이크 버튼: 듣기·말하기 중이면 멈추고, 아니면 음성 대화를 시작한다. (권한은 화면에서 먼저 확인) */
    fun microphoneTapped() {
        val ui = _ui.value
        when {
            _voiceActive.value || ui.isListening || ui.isSpeaking -> stopVoice()
            !voiceInput.isAvailable() -> appendSystem("이 기기에서 음성 인식을 사용할 수 없습니다. Google 앱(음성 인식)이 설치되어 있는지 확인하세요.")
            !ui.micPermission -> appendSystem("마이크 권한이 필요합니다. 마이크 버튼을 다시 눌러 권한을 허용하세요.")
            ui.state.isBusy -> Unit
            else -> startVoiceSession(greet = false)
        }
    }

    /**
     * 호출어("자비스")가 감지되었을 때 호출어 서비스가 부른다.
     * 이미 처리 중이면 무시하고 false를 돌려준다 (서비스는 계속 호출어를 기다린다).
     */
    fun onWakeWordDetected(): Boolean {
        val ui = _ui.value
        if (_voiceActive.value || ui.state.isBusy || !ui.micPermission) return false
        startVoiceSession(greet = true)
        return true
    }

    /**
     * 어시스턴트 화면(측면 버튼·호출어·타일)에서 바로 듣기 시작한다. 진행 중인 작업이 있으면 중단하고 새로 듣는다.
     * 권한이 없거나 인식기를 쓸 수 없으면 false.
     */
    fun startAssistListening(greet: Boolean): Boolean {
        val ui = _ui.value
        if (!ui.micPermission || !voiceInput.isAvailable()) return false
        if (_voiceActive.value) return true
        if (ui.state.isBusy) cancelCurrent()
        startVoiceSession(greet)
        return true
    }

    private fun startVoiceSession(greet: Boolean) {
        _voiceActive.value = true
        _ui.update { it.copy(voiceSessionActive = true) }
        voiceJob = scope.launch {
            try {
                runVoiceSession(greet)
            } finally {
                _voiceActive.value = false
                _ui.update { it.copy(voiceSessionActive = false) }
            }
        }
    }

    /** 듣기 → 처리 → 답변 읽기. 대화 모드면 반복한다. */
    private suspend fun runVoiceSession(greet: Boolean) {
        if (greet) speak("네, 말씀하세요.")
        do {
            val heard = listenOnce() ?: return
            val answer = process(heard) ?: return
            if (_ui.value.voiceSettings.speakReplies) speak(answer)
        } while (_ui.value.voiceSettings.continuousConversation)
    }

    /** 한 번 듣고 인식한 문장을 돌려준다. 실패·무음이면 null. */
    private suspend fun listenOnce(): String? {
        returnToIdleJob?.cancel()
        move(JarvisState.Listening)
        _ui.update { it.copy(isListening = true, partialTranscript = null, audioLevel = 0f) }
        var failure: VoiceEvent.Failure? = null
        val final = try {
            voiceInput.listen(_ui.value.voiceSettings.language)
                .onEach { event ->
                    when (event) {
                        is VoiceEvent.Partial -> _ui.update { it.copy(partialTranscript = event.text) }
                        is VoiceEvent.Level -> _ui.update { it.copy(audioLevel = event.value) }
                        is VoiceEvent.Failure -> failure = event
                        else -> Unit
                    }
                }
                .first { it is VoiceEvent.Final || it is VoiceEvent.Failure } as? VoiceEvent.Final
        } catch (e: NoSuchElementException) {
            null
        } finally {
            _ui.update { it.copy(isListening = false, partialTranscript = null, audioLevel = 0f) }
        }
        if (final == null) {
            if (_ui.value.state == JarvisState.Listening) _ui.update { it.copy(machine = it.machine.reset()) }
            failure?.let { appendSystem(if (it.silence) "${it.message} 듣기를 멈췄습니다." else it.message) }
            return null
        }
        return final.text
    }

    private suspend fun speak(text: String) {
        val spoken = SpeechText.toSpeakable(text)
        if (spoken.isBlank()) return
        _ui.update { it.copy(isSpeaking = true, audioLevel = 0.35f) }
        try {
            voiceOutput.speak(spoken, _ui.value.voiceSettings).onFailure { appendSystem(it.message ?: "음성 출력 오류") }
        } finally {
            _ui.update { it.copy(isSpeaking = false, audioLevel = 0f) }
        }
    }

    /** 듣기·말하기·음성 요청 처리를 모두 멈춘다. */
    fun stopVoice() {
        val wasActive = _voiceActive.value
        voiceJob?.cancel()
        voiceOutput.stop()
        _ui.update { it.copy(isListening = false, isSpeaking = false, partialTranscript = null, audioLevel = 0f) }
        if (wasActive && _ui.value.state.isBusy) cancelCurrent()
    }

    // endregion

    /** 진행 중인 처리를 중단한다 (스트림 연결을 끊어 백엔드 처리도 멈춘다). */
    fun cancelCurrent() {
        assistJob?.cancel()
        voiceJob?.cancel()
        voiceOutput.stop()
        returnToIdleJob?.cancel()
        activeTaskId = null
        _ui.update {
            it.copy(
                tasks = it.tasks.cancelAll(),
                machine = it.machine.reset(),
                pendingRoute = null,
                isListening = false,
                isSpeaking = false,
                partialTranscript = null,
                audioLevel = 0f,
            )
        }
    }

    fun clearConversation() {
        _ui.update { it.copy(log = it.log.cleared()) }
    }

    // endregion

    // region 설정

    fun setBackendUrl(url: String) {
        scope.launch { settings.setBackendUrl(url) }
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
        returnToIdleJob = scope.launch {
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

}
