package com.jarvis.core.state

import com.jarvis.core.model.AiEngine

/** JARVIS 작동 상태. 중앙 AI 코어와 상태 표시가 이 값을 표시한다. */
sealed interface JarvisState {
    val kind: Kind
    val displayName: String
    val hudCode: String

    /** 현재 상태에서 작동 중인 엔진. */
    val activeEngine: AiEngine?
        get() = when (this) {
            NvidiaResponding -> AiEngine.NVIDIA
            GoogleSearching -> AiEngine.GOOGLE
            ClaudeReasoning -> AiEngine.CLAUDE
            Analyzing, ExecutingTask -> AiEngine.LOCAL
            Idle, Listening, Completed, is Error -> null
        }

    /** 사용자가 중단할 수 있는 처리 중 상태인지. */
    val isBusy: Boolean
        get() = when (this) {
            Listening, Analyzing, NvidiaResponding, GoogleSearching, ClaudeReasoning, ExecutingTask -> true
            Idle, Completed, is Error -> false
        }

    data object Idle : JarvisState {
        override val kind = Kind.IDLE
        override val displayName = "대기 중"
        override val hudCode = "STANDBY"
    }

    data object Listening : JarvisState {
        override val kind = Kind.LISTENING
        override val displayName = "음성 인식 중"
        override val hudCode = "LISTENING"
    }

    data object Analyzing : JarvisState {
        override val kind = Kind.ANALYZING
        override val displayName = "사용자 명령 분석 중"
        override val hudCode = "ANALYZING"
    }

    data object NvidiaResponding : JarvisState {
        override val kind = Kind.NVIDIA_RESPONDING
        override val displayName = "NVIDIA 응답 중"
        override val hudCode = "NVIDIA · RESPONDING"
    }

    data object GoogleSearching : JarvisState {
        override val kind = Kind.GOOGLE_SEARCHING
        override val displayName = "Google 검색 중"
        override val hudCode = "GOOGLE · SEARCHING"
    }

    data object ClaudeReasoning : JarvisState {
        override val kind = Kind.CLAUDE_REASONING
        override val displayName = "Claude 추론 중"
        override val hudCode = "CLAUDE · REASONING"
    }

    data object ExecutingTask : JarvisState {
        override val kind = Kind.EXECUTING_TASK
        override val displayName = "작업 실행 중"
        override val hudCode = "EXECUTING"
    }

    data object Completed : JarvisState {
        override val kind = Kind.COMPLETED
        override val displayName = "작업 완료"
        override val hudCode = "COMPLETE"
    }

    data class Error(val message: String) : JarvisState {
        override val kind = Kind.ERROR
        override val displayName = "오류 발생"
        override val hudCode = "ERROR"
    }

    /** 전이 규칙 비교용 판별자 (오류 메시지와 무관). */
    enum class Kind {
        IDLE, LISTENING, ANALYZING, NVIDIA_RESPONDING, GOOGLE_SEARCHING,
        CLAUDE_REASONING, EXECUTING_TASK, COMPLETED, ERROR,
    }

    companion object {
        // 주의: 반드시 getter로 둔다. 필드로 초기화하면 data object 초기화 중 인터페이스 초기화가
        // 먼저 실행되어 목록에 null이 들어가는 순환 초기화 문제가 생긴다 (테스트로 확인됨).
        val all: List<JarvisState>
            get() = listOf(
            Idle, Listening, Analyzing, NvidiaResponding, GoogleSearching,
            ClaudeReasoning, ExecutingTask, Completed, Error("예시 오류"),
        )
    }
}
