package com.jarvis.core

import com.jarvis.core.routing.CommandClassifier
import com.jarvis.core.routing.Route
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandClassifierTest {
    private fun assertRoute(expected: Route, vararg inputs: String) {
        for (input in inputs) {
            assertEquals(expected, CommandClassifier.classify(input).route, "\"$input\"")
        }
    }

    @Test
    fun everydayConversationGoesToNvidia() = assertRoute(
        Route.CHAT,
        "안녕 자비스",
        "자비스, 기분이 어때?",
        "고마워",
        "농담 하나 해줘",
        "블랙홀이 뭐야?",
        "How are you?",
    )

    @Test
    fun freshInformationGoesToGoogleSearch() = assertRoute(
        Route.SEARCH,
        "자비스 오늘 서울 날씨 어때?",
        "최신 아이폰 가격 알려줘",
        "요즘 AI 뉴스 뭐 있어?",
        "원달러 환율",
        "인공지능 관련 논문 검색해줘",
        "latest news about SpaceX",
    )

    @Test
    fun searchPlusAnalysisGoesToClaudeResearch() = assertRoute(
        Route.RESEARCH,
        "최신 전기차 시장 동향을 조사해서 분석해줘",
        "요즘 나온 스마트폰 3개 가격 비교해줘",
        "오늘 뉴스 정리해서 요약해줘",
    )

    @Test
    fun complexWorkGoesToClaudeAgent() = assertRoute(
        Route.AGENT,
        "자비스, 새로운 프로젝트를 만들어.",
        "파이썬으로 자동화 프로그램을 제작해",
        "이 아이디어를 프로젝트로 만들어",
        "우주에 관한 30초짜리 영상을 기획해",
        "이 주제로 영상 대본을 작성해",
        "여행 일정 계획 세워줘",
        "먼저 자료를 모으고 그 다음에 보고서 초안을 써줘",
        "장단점을 분석해줘",
    )

    @Test
    fun deviceCommandsStayLocal() = assertRoute(
        Route.DEVICE,
        "내일 오후 3시에 회의 일정 추가해줘",
        "아침 7시에 알람 맞춰줘",
        "우유 사기 미리 알림 등록해",
        "메모 적어줘: 주차 B2",
        "set an alarm for 6am",
    )

    @Test
    fun wakeWordIsIgnoredAndEmptyInputIsChat() {
        assertEquals(Route.CHAT, CommandClassifier.classify("자비스").route)
        assertEquals(Route.SEARCH, CommandClassifier.classify("Jarvis, 오늘 뉴스").route)
    }
}
