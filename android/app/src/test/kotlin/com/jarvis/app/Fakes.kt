package com.jarvis.app

import com.jarvis.app.data.SettingsStore
import com.jarvis.app.data.TokenStore
import com.jarvis.app.voice.VoiceEvent
import com.jarvis.app.voice.VoiceInput
import com.jarvis.app.voice.VoiceOutput
import com.jarvis.core.voice.VoiceSettings
import kotlinx.coroutines.CompletableDeferred
import com.jarvis.core.network.AssistEvent
import com.jarvis.core.network.AssistService
import com.jarvis.core.network.HistoryTurn
import com.jarvis.core.network.HttpResponse
import com.jarvis.core.network.HttpTransport
import com.jarvis.core.routing.Route
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

class FakeSettingsStore(initialUrl: String = "", voice: VoiceSettings = VoiceSettings()) : SettingsStore {
    private val url = MutableStateFlow(initialUrl)
    private val voiceFlow = MutableStateFlow(voice)
    override val backendUrl: StateFlow<String> = url
    override suspend fun setBackendUrl(url: String) {
        this.url.value = url.trim()
    }
    override val voiceSettings: StateFlow<VoiceSettings> = voiceFlow
    override suspend fun setVoiceSettings(settings: VoiceSettings) {
        voiceFlow.value = settings
    }
}

/** 듣기 호출마다 준비된 이벤트 묶음을 차례로 내보내는 가짜 음성 인식. 묶음이 떨어지면 무음 실패. */
class FakeVoiceInput(vararg scripts: List<VoiceEvent>, private val available: Boolean = true) : VoiceInput {
    private val queue = ArrayDeque(scripts.toList())
    val languages = mutableListOf<String>()
    override fun isAvailable() = available
    override fun listen(language: String): Flow<VoiceEvent> {
        languages += language
        val events = queue.removeFirstOrNull() ?: listOf(VoiceEvent.Failure(6, "음성이 들리지 않았어요.", silence = true))
        return flow { events.forEach { emit(it) } }
    }

    companion object {
        fun said(text: String) = listOf(VoiceEvent.Ready, VoiceEvent.Partial(text.take(2)), VoiceEvent.Level(0.5f), VoiceEvent.Final(text))
    }
}

/** 말한 문장을 기록하는 가짜 TTS. hang=true면 stop() 될 때까지 말하는 중으로 머문다. */
class FakeVoiceOutput(private val hang: Boolean = false) : VoiceOutput {
    val spoken = mutableListOf<String>()
    var stopped = 0
    private var pending: CompletableDeferred<Unit>? = null
    override suspend fun speak(text: String, settings: VoiceSettings): Result<Unit> {
        spoken += text
        if (hang) CompletableDeferred<Unit>().also { pending = it }.await()
        return Result.success(Unit)
    }
    override fun stop() {
        stopped++
        pending?.complete(Unit)
    }
}

class FakeTokenStore(private var token: String? = null) : TokenStore {
    override fun currentToken(): String? = token
    override fun save(token: String) {
        this.token = token
    }
    override fun delete() {
        token = null
    }
}

/** 경로별 고정 응답. 등록되지 않은 경로는 404. */
class StubTransport(private val routes: Map<String, Pair<Int, String>> = emptyMap()) : HttpTransport {
    val requestedUrls = mutableListOf<String>()
    override suspend fun send(request: com.jarvis.core.network.HttpRequest): HttpResponse {
        requestedUrls += request.url
        val path = java.net.URI(request.url).path
        val (status, body) = routes[path] ?: (404 to """{"detail":"not found"}""")
        return HttpResponse(status, body)
    }
}

/** 미리 정한 이벤트를 내보내는 가짜 AI 서비스. */
class FakeAssistService(
    private vararg val events: AssistEvent,
    private val hang: Boolean = false,
    private val throwOnStart: Exception? = null,
) : AssistService {
    val calls = mutableListOf<Triple<Route, String, List<HistoryTurn>>>()
    var cancelled = false

    override fun assist(route: Route, text: String, history: List<HistoryTurn>): Flow<AssistEvent> {
        calls += Triple(route, text, history)
        return flow {
            throwOnStart?.let { throw it }
            events.forEach { emit(it) }
            if (hang) {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
    }
}
