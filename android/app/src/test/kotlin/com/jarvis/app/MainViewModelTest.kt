package com.jarvis.app

import com.jarvis.app.ui.main.MainViewModel
import com.jarvis.core.model.AiEngine
import com.jarvis.core.model.ConnectionStatus
import com.jarvis.core.model.ConversationMessage
import com.jarvis.core.model.JarvisFeature
import com.jarvis.core.model.JarvisTask
import com.jarvis.core.model.SourceLink
import com.jarvis.core.network.AssistEvent
import com.jarvis.core.network.BackendException
import com.jarvis.core.network.HistoryTurn
import com.jarvis.core.routing.Route
import com.jarvis.core.state.JarvisState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val healthOk = 200 to """{"status":"ok","service":"jarvis-backend","version":"0.1.0"}"""

    private fun model(
        url: String = "",
        token: String? = "t",
        routes: Map<String, Pair<Int, String>> = emptyMap(),
        assist: FakeAssistService = FakeAssistService(),
    ) = MainViewModel(FakeSettingsStore(url), FakeTokenStore(token), StubTransport(routes), { error("unused") }, { assist })

    @Test
    fun chatStreamsNvidiaAnswerIntoConversation() = runTest(dispatcher) {
        val assist = FakeAssistService(
            AssistEvent.RouteInfo(AiEngine.NVIDIA, "nemotron"),
            AssistEvent.StageChange("responding", null),
            AssistEvent.Delta("안녕하세요, "),
            AssistEvent.Delta("무엇을 도와드릴까요?"),
            AssistEvent.Done(AiEngine.NVIDIA, "nemotron", false, 10, 5, 0),
        )
        val vm = model(url = "http://10.0.2.2:8000", assist = assist)
        runCurrent()
        vm.submit("  자비스 안녕  ")
        runCurrent()

        val ui = vm.ui.value
        assertEquals(listOf(Route.CHAT to "자비스 안녕"), assist.calls.map { it.first to it.second })
        val answer = ui.log.messages.last()
        assertEquals(ConversationMessage.Role.ASSISTANT, answer.role)
        assertEquals("안녕하세요, 무엇을 도와드릴까요?", answer.text)
        assertEquals(AiEngine.NVIDIA, answer.engine)
        assertFalse(answer.isStreaming)
        assertEquals(JarvisState.Completed, ui.state)
        assertEquals(JarvisTask.Status.SUCCEEDED, ui.tasks.finishedTasks.last().status)
        assertEquals(listOf(JarvisState.Analyzing, JarvisState.NvidiaResponding, JarvisState.Completed),
            ui.machine.history.map { it.to })

        advanceTimeBy(1_600)
        assertEquals(JarvisState.Idle, vm.ui.value.state, "완료 후 잠시 뒤 대기 상태로 돌아간다")
    }

    @Test
    fun searchAttachesSourcesAndSuggestionsToAnswer() = runTest(dispatcher) {
        val assist = FakeAssistService(
            AssistEvent.RouteInfo(AiEngine.GOOGLE, "gemini-4-hydrogen"),
            AssistEvent.Delta("스페인이 우승했습니다."),
            AssistEvent.SourcesInfo("google", listOf(SourceLink("uefa.com", "https://uefa.com")), listOf("euro"), "<div/>"),
            AssistEvent.Done(AiEngine.GOOGLE, "gemini-4-hydrogen", false, 1, 2, 1),
        )
        val vm = model(url = "https://jarvis.example.com", assist = assist)
        runCurrent()
        vm.submit("오늘 뉴스 알려줘")
        runCurrent()
        assertEquals(Route.SEARCH, assist.calls.single().first)
        val answer = vm.ui.value.log.messages.last()
        assertEquals("스페인이 우승했습니다.", answer.text)
        assertEquals("google", answer.sourceProvider)
        assertEquals("<div/>", answer.searchSuggestionsHtml)
        assertEquals(1, answer.sources.size)
        assertTrue(JarvisState.GoogleSearching in vm.ui.value.machine.history.map { it.to })
    }

    @Test
    fun searchQuickActionForcesNextInputToSearch() = runTest(dispatcher) {
        val assist = FakeAssistService(AssistEvent.Done(AiEngine.GOOGLE, "g", false, 0, 0, 0))
        val vm = model(url = "https://jarvis.example.com", assist = assist)
        runCurrent()
        vm.selectFeature(JarvisFeature.SEARCH)
        assertEquals(Route.SEARCH, vm.ui.value.pendingRoute)
        vm.submit("고양이") // 분류기라면 CHAT이지만 빠른 기능 선택으로 SEARCH
        runCurrent()
        assertEquals(Route.SEARCH, assist.calls.single().first)
        assertEquals(null, vm.ui.value.pendingRoute)
    }

    @Test
    fun historyIsSentWithoutSystemMessages() = runTest(dispatcher) {
        val assist = FakeAssistService(AssistEvent.Delta("응답"), AssistEvent.Done(AiEngine.NVIDIA, "m", false, 0, 0, 0))
        val vm = model(url = "https://jarvis.example.com", assist = assist)
        runCurrent()
        vm.submit("첫 질문")
        runCurrent()
        advanceTimeBy(1_600)
        vm.selectFeature(JarvisFeature.VIDEO) // 시스템 안내 — 기록에서 제외되어야 함
        vm.submit("두 번째 질문")
        runCurrent()
        assertEquals(
            listOf(HistoryTurn("user", "첫 질문"), HistoryTurn("assistant", "응답")),
            assist.calls.last().third,
        )
    }

    @Test
    fun agentFailureShowsErrorState() = runTest(dispatcher) {
        val assist = FakeAssistService(
            AssistEvent.RouteInfo(AiEngine.CLAUDE, "claude-opus-5-5"),
            AssistEvent.Failure("Claude API 요청 한도를 초과했습니다.", "rate_limited", true),
        )
        val vm = model(url = "https://jarvis.example.com", assist = assist)
        runCurrent()
        vm.submit("파이썬 자동화 프로젝트 기획해줘")
        runCurrent()
        assertEquals(Route.AGENT, assist.calls.single().first)
        val ui = vm.ui.value
        assertIs<JarvisState.Error>(ui.state)
        assertEquals("Claude API 요청 한도를 초과했습니다.", ui.log.last?.text)
        assertEquals(JarvisTask.Status.FAILED, ui.tasks.finishedTasks.last().status)
    }

    @Test
    fun fallbackNoticeIsShownAndBackendErrorsAreReported() = runTest(dispatcher) {
        val assist = FakeAssistService(
            AssistEvent.RouteInfo(AiEngine.NVIDIA, "m"),
            AssistEvent.Notice("Claude를 사용할 수 없어 NVIDIA(으)로 대신 처리합니다."),
            AssistEvent.Delta("대체 응답"),
            AssistEvent.Done(AiEngine.NVIDIA, "m", false, 0, 0, 0),
        )
        val vm = model(url = "https://jarvis.example.com", assist = assist)
        runCurrent()
        vm.submit("보고서 작성해줘")
        runCurrent()
        assertTrue(vm.ui.value.log.messages.any { it.role == ConversationMessage.Role.SYSTEM && it.text.contains("대신 처리") })

        val failing = model(url = "https://jarvis.example.com", assist = FakeAssistService(throwOnStart = BackendException.Http(503, "NVIDIA 엔진을 사용할 수 없습니다")))
        runCurrent()
        failing.submit("안녕")
        runCurrent()
        assertIs<JarvisState.Error>(failing.ui.value.state)
        assertTrue(failing.ui.value.log.last!!.text.contains("NVIDIA 엔진을 사용할 수 없습니다"))
    }

    @Test
    fun cancelStopsStreaming() = runTest(dispatcher) {
        val assist = FakeAssistService(AssistEvent.RouteInfo(AiEngine.CLAUDE, "c"), AssistEvent.Delta("일부"), hang = true)
        val vm = model(url = "https://jarvis.example.com", assist = assist)
        runCurrent()
        vm.submit("프로젝트 기획해줘")
        runCurrent()
        assertEquals(JarvisState.ClaudeReasoning, vm.ui.value.state)
        vm.cancelCurrent()
        runCurrent()
        assertEquals(JarvisState.Idle, vm.ui.value.state)
        assertTrue(assist.cancelled, "스트림 수집이 취소되어야 한다")
        assertFalse(vm.ui.value.log.messages.last().isStreaming)
    }

    @Test
    fun deviceCommandIsNotSentToBackend() = runTest(dispatcher) {
        val assist = FakeAssistService()
        val vm = model(url = "https://jarvis.example.com", assist = assist)
        runCurrent()
        vm.submit("내일 7시에 알람 맞춰줘")
        runCurrent()
        assertTrue(assist.calls.isEmpty())
        assertTrue(vm.ui.value.log.last!!.text.contains("PHASE 4"))
        assertEquals(JarvisState.Completed, vm.ui.value.state)
    }

    @Test
    fun blankInputIsIgnored() = runTest(dispatcher) {
        val vm = model()
        vm.submit("   ")
        assertTrue(vm.ui.value.log.isEmpty)
        assertEquals(JarvisState.Idle, vm.ui.value.state)
    }

    @Test
    fun missingBackendUrlShowsNotConfigured() = runTest(dispatcher) {
        val vm = model()
        vm.refreshEngineStatus()
        advanceUntilIdle()
        assertEquals(ConnectionStatus.NotConfigured, vm.ui.value.backend)
    }

    @Test
    fun refreshMapsEngineStatuses() = runTest(dispatcher) {
        val vm = model(
            url = "https://jarvis.example.com",
            routes = mapOf(
                "/health" to healthOk,
                "/v1/engines" to (200 to """{"engines":[{"id":"nvidia","configured":true,"provider":"NVIDIA","model":"m"},{"id":"claude","configured":false,"provider":"Anthropic","model":"claude-opus-5-5"}]}"""),
            ),
        )
        vm.refreshEngineStatus()
        advanceUntilIdle()
        val ui = vm.ui.value
        assertEquals(ConnectionStatus.Connected, ui.backend)
        assertEquals(ConnectionStatus.Configured, ui.engines[AiEngine.NVIDIA])
        assertEquals(ConnectionStatus.NotConfigured, ui.engines[AiEngine.CLAUDE])
        assertEquals(ConnectionStatus.NotConfigured, ui.engines[AiEngine.GOOGLE])
        assertEquals(JarvisTask.Status.SUCCEEDED, ui.tasks.finishedTasks.last().status)
    }

    @Test
    fun wrongTokenShowsMessageButBackendStaysConnected() = runTest(dispatcher) {
        val vm = model(
            url = "http://10.0.2.2:8000",
            routes = mapOf("/health" to healthOk, "/v1/engines" to (401 to """{"detail":"bad"}""")),
        )
        vm.refreshEngineStatus()
        advanceUntilIdle()
        val ui = vm.ui.value
        assertEquals(ConnectionStatus.Connected, ui.backend)
        assertEquals(JarvisTask.Status.FAILED, ui.tasks.finishedTasks.last().status)
        assertEquals("앱 인증 토큰이 올바르지 않습니다.", ui.log.last?.text)
    }

    @Test
    fun insecureUrlIsRejectedWithoutNetwork() = runTest(dispatcher) {
        val transport = StubTransport()
        val vm = MainViewModel(FakeSettingsStore("http://jarvis.example.com"), FakeTokenStore("t"), transport, { error("unused") })
        vm.refreshEngineStatus()
        advanceUntilIdle()
        assertIs<ConnectionStatus.Unreachable>(vm.ui.value.backend)
        assertTrue(transport.requestedUrls.isEmpty())
    }

    @Test
    fun unimplementedFeatureProducesNotice() = runTest(dispatcher) {
        val vm = model()
        vm.selectFeature(JarvisFeature.VIDEO)
        assertTrue(vm.ui.value.log.last!!.text.contains("PHASE 6"))
    }

    @Test
    fun tokenSaveAndDelete() = runTest(dispatcher) {
        val vm = model(token = null)
        assertFalse(vm.ui.value.hasToken)
        assertTrue(vm.saveToken("  abc  ").isSuccess)
        assertTrue(vm.ui.value.hasToken)
        assertTrue(vm.deleteToken().isSuccess)
        assertFalse(vm.ui.value.hasToken)
    }

    @Test
    fun cancelReturnsToIdle() = runTest(dispatcher) {
        val vm = model()
        vm.debugForceState(JarvisState.ClaudeReasoning)
        vm.cancelCurrent()
        assertEquals(JarvisState.Idle, vm.ui.value.state)
    }
}
