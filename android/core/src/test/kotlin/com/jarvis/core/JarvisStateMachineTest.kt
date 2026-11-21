package com.jarvis.core

import com.jarvis.core.model.AiEngine
import com.jarvis.core.state.JarvisState
import com.jarvis.core.state.JarvisStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JarvisStateMachineTest {
    @Test
    fun startsIdle() {
        val machine = JarvisStateMachine()
        assertEquals(JarvisState.Idle, machine.state)
        assertFalse(machine.state.isBusy)
        assertTrue(machine.history.isEmpty())
    }

    @Test
    fun voiceConversationFlow() {
        val machine = JarvisStateMachine()
            .transition(JarvisState.Listening)
            .transition(JarvisState.Analyzing)
            .transition(JarvisState.NvidiaResponding)
            .transition(JarvisState.Completed)
            .transition(JarvisState.Idle)
        assertEquals(JarvisState.Idle, machine.state)
        assertEquals(5, machine.history.size)
        assertEquals(JarvisState.Idle, machine.history.first().from)
        assertEquals(JarvisState.Listening, machine.history.first().to)
    }

    @Test
    fun agentToolLoopFlow() {
        val machine = JarvisStateMachine()
            .transition(JarvisState.Analyzing)
            .transition(JarvisState.Reasoning)
            .transition(JarvisState.GoogleSearching)
            .transition(JarvisState.Reasoning)
            .transition(JarvisState.ExecutingTask)
            .transition(JarvisState.Reasoning)
            .transition(JarvisState.Completed)
        assertEquals(JarvisState.Completed, machine.state)
    }

    @Test
    fun rejectsSkippingAnalysis() {
        val machine = JarvisStateMachine()
        val error = assertFailsWith<JarvisStateMachine.TransitionNotAllowed> {
            machine.transition(JarvisState.Reasoning)
        }
        assertEquals(JarvisState.Kind.IDLE, error.from)
        assertEquals(JarvisState.Kind.REASONING, error.to)
        assertEquals(JarvisState.Idle, machine.state, "거부된 전이는 상태를 바꾸지 않아야 한다")
    }

    @Test
    fun errorAndIdleReachableFromEveryState() {
        for (state in JarvisState.all) {
            assertTrue(JarvisStateMachine.canTransition(state, JarvisState.Error("e")), "$state → error")
            assertTrue(JarvisStateMachine.canTransition(state, JarvisState.Idle), "$state → idle")
        }
    }

    @Test
    fun errorMessageUpdateIsRecorded() {
        val machine = JarvisStateMachine()
            .transition(JarvisState.Error("첫 오류"))
            .transition(JarvisState.Error("두 번째 오류"))
        assertEquals(JarvisState.Error("두 번째 오류"), machine.state)
        assertEquals(2, machine.history.size)
    }

    @Test
    fun sameStateTransitionIsNoOp() {
        val listening = JarvisStateMachine().transition(JarvisState.Listening)
        assertSame(listening, listening.transition(JarvisState.Listening))
    }

    @Test
    fun resetCancelsBusyState() {
        val machine = JarvisStateMachine()
            .transition(JarvisState.Analyzing)
            .transition(JarvisState.Reasoning)
            .reset()
        assertEquals(JarvisState.Idle, machine.state)
        assertEquals(3, machine.reset().history.size, "이미 대기 중이면 reset은 기록을 남기지 않는다")
    }

    @Test
    fun historyIsBounded() {
        var machine = JarvisStateMachine(maxHistory = 4)
        repeat(10) {
            machine = machine.transition(JarvisState.Listening).transition(JarvisState.Idle)
        }
        assertEquals(4, machine.history.size)
    }

    @Test
    fun koreanNamesAndEngineMapping() {
        val names = JarvisState.all.map { it.displayName }
        assertEquals(
            listOf(
                "대기 중", "음성 인식 중", "사용자 명령 분석 중", "NVIDIA 응답 중", "Google 검색 중",
                "심층 추론 중", "작업 실행 중", "작업 완료", "오류 발생",
            ),
            names,
        )
        assertEquals(AiEngine.NVIDIA, JarvisState.NvidiaResponding.activeEngine)
        assertEquals(AiEngine.GOOGLE, JarvisState.GoogleSearching.activeEngine)
        assertEquals(AiEngine.NVIDIA, JarvisState.Reasoning.activeEngine)
        assertNull(JarvisState.Idle.activeEngine)
        assertEquals(9, JarvisState.Kind.entries.size)
    }
}
