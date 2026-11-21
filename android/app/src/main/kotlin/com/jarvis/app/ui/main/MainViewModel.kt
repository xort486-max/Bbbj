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
import com.jarvis.core.network.BackendClient
import com.jarvis.core.network.BackendConfiguration
import com.jarvis.core.network.EngineStatusMapper
import com.jarvis.core.network.HttpTransport
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
 * PHASE 1 범위: 상태 머신, 대화 기록, 작업 표시, 백엔드 연결 상태 확인.
 * AI 응답(NVIDIA/Google/Claude)은 PHASE 2에서 CommandRouter를 통해 연결한다.
 */
class MainViewModel(
    private val settings: SettingsStore,
    private val tokens: TokenStore,
    private val transport: HttpTransport,
) : ViewModel() {

    private val _ui = MutableStateFlow(MainUiState(hasToken = tokens.hasToken()))
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    val backendUrl: StateFlow<String> =
        settings.backendUrl.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private var refreshJob: Job? = null
    private var returnToIdleJob: Job? = null

    // region 백엔드 상태

    /** 백엔드 `/health`와 `/v1/engines`를 호출해 연결 상태를 갱신한다. */
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
            _ui.update {
                it.copy(
                    engines = statuses,
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

    // endregion

    // region 사용자 명령

    fun submit(input: String) {
        val text = input.trim()
        if (text.isEmpty() || _ui.value.state.isBusy) return
        returnToIdleJob?.cancel()

        _ui.update { it.copy(log = it.log.append(ConversationMessage(ConversationMessage.Role.USER, text))) }
        move(JarvisState.Analyzing)

        // PHASE 2에서 로컬 분류기 → NVIDIA / Google / Claude / 로컬 엔진 라우팅으로 대체된다.
        // 연결되지 않은 엔진의 응답을 흉내 내지 않고 현재 상태를 그대로 안내한다.
        appendSystem(
            "명령을 수신했습니다. AI 엔진 라우팅(NVIDIA 대화 · Google 검색 · Claude 에이전트)은 PHASE 2에서 연결됩니다. " +
                "현재 버전은 인터페이스와 백엔드 상태 확인만 동작합니다.",
        )
        move(JarvisState.Completed)
        returnToIdleJob = viewModelScope.launch {
            delay(1_500)
            if (_ui.value.state == JarvisState.Completed) _ui.update { it.copy(machine = it.machine.reset()) }
        }
    }

    fun selectFeature(feature: JarvisFeature) {
        if (feature.isImplemented) return
        appendSystem("${feature.title} 기능은 ${feature.availabilityLabel}입니다.")
    }

    fun microphoneTapped() {
        // PHASE 3: RECORD_AUDIO 권한 요청 및 SpeechRecognizer 시작.
        appendSystem("음성 인식은 PHASE 3에서 구현됩니다.")
    }

    /** 진행 중인 처리를 중단한다. */
    fun cancelCurrent() {
        returnToIdleJob?.cancel()
        _ui.update { it.copy(tasks = it.tasks.cancelAll(), machine = it.machine.reset()) }
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
            initializer { MainViewModel(container.settings, container.tokens, container.transport) }
        }
    }
}
