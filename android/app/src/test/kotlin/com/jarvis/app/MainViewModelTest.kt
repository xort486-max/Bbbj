package com.jarvis.app

import com.jarvis.app.ui.main.MainViewModel
import com.jarvis.core.model.AiEngine
import com.jarvis.core.model.ConnectionStatus
import com.jarvis.core.model.ConversationMessage
import com.jarvis.core.model.JarvisFeature
import com.jarvis.core.model.JarvisTask
import com.jarvis.core.state.JarvisState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
    ) = MainViewModel(FakeSettingsStore(url), FakeTokenStore(token), StubTransport(routes))

    @Test
    fun submitAppendsUserMessageAndHonestNotice() = runTest(dispatcher) {
        val vm = model()
        vm.submit("  안녕 자비스  ")
        val ui = vm.ui.value
        assertEquals(ConversationMessage.Role.USER, ui.log.messages.first().role)
        assertEquals("안녕 자비스", ui.log.messages.first().text)
        // PHASE 1에서는 AI 응답을 흉내 내지 않고 시스템 안내만 표시해야 한다.
        assertFalse(ui.log.messages.any { it.role == ConversationMessage.Role.ASSISTANT })
        assertEquals(ConversationMessage.Role.SYSTEM, ui.log.last?.role)
        assertEquals(JarvisState.Completed, ui.state)

        advanceTimeBy(1_600)
        assertEquals(JarvisState.Idle, vm.ui.value.state, "완료 후 잠시 뒤 대기 상태로 돌아간다")
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
        val vm = MainViewModel(FakeSettingsStore("http://jarvis.example.com"), FakeTokenStore("t"), transport)
        vm.refreshEngineStatus()
        advanceUntilIdle()
        assertIs<ConnectionStatus.Unreachable>(vm.ui.value.backend)
        assertTrue(transport.requestedUrls.isEmpty())
    }

    @Test
    fun unimplementedFeatureProducesNotice() = runTest(dispatcher) {
        val vm = model()
        vm.selectFeature(JarvisFeature.SEARCH)
        assertTrue(vm.ui.value.log.last!!.text.contains("PHASE 2"))
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
