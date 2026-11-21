package com.jarvis.core.model

/**
 * 빠른 기능 실행 메뉴에 노출되는 기능과 구현 상태.
 *
 * 구현되지 않은 기능이 동작하는 것처럼 보이지 않도록, 각 기능은 구현 여부와 예정 단계를 가진다.
 * 기능을 실제로 구현하는 단계에서 [isImplemented]를 갱신한다.
 */
enum class JarvisFeature(val title: String, val plannedPhase: Int) {
    VOICE("음성", 3),
    SEARCH("검색", 2),
    CALENDAR("일정", 4),
    ALARM("알람", 4),
    NOTE("메모", 4),
    PROJECT("프로젝트", 5),
    VIDEO("영상", 6);

    /** 실제로 동작하는 구현이 앱에 들어 있는지. PHASE 1에서는 모두 false. */
    val isImplemented: Boolean
        get() = when (this) {
            VOICE, SEARCH, CALENDAR, ALARM, NOTE, PROJECT, VIDEO -> false
        }

    val availabilityLabel: String
        get() = if (isImplemented) "사용 가능" else "PHASE $plannedPhase 예정"
}
