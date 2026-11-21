package com.jarvis.core.model

/**
 * JARVIS가 요청을 분배하는 실행 엔진.
 *
 * - [NVIDIA]: 대화 + 에이전트 엔진 (NVIDIA API Catalog — 대화용 소형 모델, 기획·코드용 대형 추론 모델)
 * - [GOOGLE]: 검색 엔진 (Gemini API Grounding with Google Search — 검색·검색 분석)
 * - [LOCAL]: 기기 내 명령 엔진 (분류기, 캘린더, 알람, 파일 등)
 */
enum class AiEngine(val id: String, val displayName: String, val roleDescription: String) {
    NVIDIA("nvidia", "NVIDIA", "대화 · 에이전트"),
    GOOGLE("google", "GOOGLE", "검색 · 분석"),
    LOCAL("local", "LOCAL", "로컬 명령 엔진");

    /** 백엔드를 거쳐야 하는 원격 엔진인지. API Key는 백엔드에만 존재한다. */
    val isRemote: Boolean get() = this != LOCAL

    companion object {
        val remoteEngines: List<AiEngine> = entries.filter { it.isRemote }

        fun fromId(id: String): AiEngine? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 엔진/백엔드 연결 상태.
 *
 * [Configured](설정값 존재)와 [Connected](실제 요청 성공)를 구분한다 —
 * 확인하지 않은 상태를 "연결됨"으로 표시하지 않기 위함.
 */
sealed interface ConnectionStatus {
    val displayName: String
    val level: Level

    data object Unknown : ConnectionStatus {
        override val displayName = "확인 전"
        override val level = Level.NEUTRAL
    }

    data object Checking : ConnectionStatus {
        override val displayName = "확인 중"
        override val level = Level.NEUTRAL
    }

    data object NotConfigured : ConnectionStatus {
        override val displayName = "미설정"
        override val level = Level.WARNING
    }

    data object Configured : ConnectionStatus {
        override val displayName = "설정됨"
        override val level = Level.OK
    }

    data object Connected : ConnectionStatus {
        override val displayName = "연결됨"
        override val level = Level.OK
    }

    data class Unreachable(val reason: String) : ConnectionStatus {
        override val displayName = "연결 실패"
        override val level = Level.FAILURE
    }

    enum class Level { NEUTRAL, OK, WARNING, FAILURE }
}
