package com.jarvis.core.state

import com.jarvis.core.state.JarvisState.Kind

/**
 * JARVIS 상태 전이를 검증하는 상태 머신 (불변 — 전이마다 새 인스턴스를 반환).
 *
 * 허용되지 않은 전이(예: 대기 중 → Claude 추론 중)를 막아 UI 표시와 실제 처리 흐름이
 * 어긋나지 않도록 한다. 모든 상태에서 [JarvisState.Error]와 [JarvisState.Idle](중단)로는 항상 이동할 수 있다.
 */
data class JarvisStateMachine(
    val state: JarvisState = JarvisState.Idle,
    /** 최근 전이 기록 (디버깅·실행 기록용). */
    val history: List<Transition> = emptyList(),
    val maxHistory: Int = 50,
) {
    data class Transition(val from: JarvisState, val to: JarvisState, val atMillis: Long)

    class TransitionNotAllowed(val from: Kind, val to: Kind) :
        IllegalStateException("허용되지 않은 상태 전이: $from → $to")

    /** 상태를 전이한다. 같은 상태로의 전이는 무시한다 (오류 메시지 변경은 반영). */
    fun transition(to: JarvisState, atMillis: Long = System.currentTimeMillis()): JarvisStateMachine {
        if (to == state) return this
        if (to.kind == Kind.ERROR && state.kind == Kind.ERROR) return record(to, atMillis)
        if (!canTransition(state, to)) throw TransitionNotAllowed(state.kind, to.kind)
        return record(to, atMillis)
    }

    /** 진행 중인 처리를 중단하고 대기 상태로 돌아간다. */
    fun reset(atMillis: Long = System.currentTimeMillis()): JarvisStateMachine =
        if (state == JarvisState.Idle) this else record(JarvisState.Idle, atMillis)

    /** 검증 없이 상태를 지정한다. Compose 미리보기와 디자인 점검(디버그)에서만 사용한다. */
    fun forced(to: JarvisState, atMillis: Long = System.currentTimeMillis()): JarvisStateMachine = record(to, atMillis)

    private fun record(to: JarvisState, atMillis: Long): JarvisStateMachine =
        copy(state = to, history = (history + Transition(state, to, atMillis)).takeLast(maxHistory))

    companion object {
        /** 상태별 허용 전이표. Error와 Idle은 [canTransition]에서 공통으로 허용한다. */
        private val allowed: Map<Kind, Set<Kind>> = mapOf(
            Kind.IDLE to setOf(Kind.LISTENING, Kind.ANALYZING),
            Kind.LISTENING to setOf(Kind.ANALYZING),
            Kind.ANALYZING to setOf(
                Kind.NVIDIA_RESPONDING, Kind.GOOGLE_SEARCHING, Kind.CLAUDE_REASONING,
                Kind.EXECUTING_TASK, Kind.COMPLETED,
            ),
            Kind.NVIDIA_RESPONDING to setOf(Kind.COMPLETED),
            // 검색 결과 분석은 Claude, 간단 요약은 NVIDIA가 담당할 수 있다.
            Kind.GOOGLE_SEARCHING to setOf(Kind.CLAUDE_REASONING, Kind.NVIDIA_RESPONDING, Kind.COMPLETED),
            // Claude Tool Use: 추론 → 검색/작업 실행 → 다시 추론 반복.
            Kind.CLAUDE_REASONING to setOf(Kind.GOOGLE_SEARCHING, Kind.EXECUTING_TASK, Kind.COMPLETED),
            Kind.EXECUTING_TASK to setOf(Kind.CLAUDE_REASONING, Kind.COMPLETED),
            Kind.COMPLETED to setOf(Kind.LISTENING, Kind.ANALYZING),
            Kind.ERROR to setOf(Kind.LISTENING, Kind.ANALYZING),
        )

        fun canTransition(from: JarvisState, to: JarvisState): Boolean {
            if (to.kind == Kind.ERROR || to.kind == Kind.IDLE) return true
            return allowed[from.kind].orEmpty().contains(to.kind)
        }
    }
}
