package com.jarvis.core.routing

/**
 * 요청 경로. [backendId]는 백엔드 `/v1/assist`의 route 값 (DEVICE는 기기 안에서 처리).
 */
enum class Route(val backendId: String?, val title: String) {
    /** 일반 대화 → NVIDIA */
    CHAT("chat", "대화"),

    /** 웹 검색 → Google (Gemini Grounding) */
    SEARCH("search", "웹 검색"),

    /** 검색 + 분석 → Google (Gemini Grounding, 비교·분석 지시) */
    RESEARCH("research", "검색 분석"),

    /** 복잡한 명령·기획·코드·문서 → NVIDIA 대형 추론 모델 */
    AGENT("agent", "AI 에이전트 작업"),

    /** 기기 기능 (일정·알람·메모 등) → 로컬 명령 엔진 (PHASE 4) */
    DEVICE(null, "기기 기능"),
}

data class Classification(val route: Route, val reason: String)

/**
 * 로컬 명령 분류기 — 규칙 기반, 기기 안에서 즉시 실행되며 비용이 들지 않는다.
 *
 * 대형 모델 호출을 줄이기 위해 "복잡한 작업"으로 분명히 보이는 요청만 AGENT로 보낸다.
 * 애매하면 가장 빠르고 저렴한 CHAT(NVIDIA 소형 모델)으로 보낸다.
 */
object CommandClassifier {
    private val wakeWord = Regex("^\\s*(자비스|jarvis)[\\s,.!~]*", RegexOption.IGNORE_CASE)

    private val deviceObject = Regex("일정|캘린더|스케줄|알람|타이머|미리\\s?알림|리마인더|메모|alarm|timer|reminder|calendar|schedule|memo", RegexOption.IGNORE_CASE)
    private val deviceAction = Regex("추가|등록|잡아|넣어|설정|맞춰|맞춰줘|만들어|적어|기록|삭제|지워|취소|알려줘|보여줘|확인|켜|꺼|add|set|create|remind|delete|show", RegexOption.IGNORE_CASE)
    private val planning = Regex("계획|기획|플랜|전략|로드맵|plan", RegexOption.IGNORE_CASE)

    private val searchCue = Regex(
        "검색|찾아\\s?봐|찾아줘|찾아\\s?줘|최신|최근|뉴스|소식|오늘|어제|이번\\s?주|요즘|현재|지금|실시간|날씨|기온|환율|주가|시세|가격|얼마|" +
            "출시|발표|결과|순위|일정표|어디서|search|latest|news|today|price|weather|current",
        RegexOption.IGNORE_CASE,
    )
    private val analysisCue = Regex(
        "분석|비교|평가|장단점|차이|정리해|요약해|리포트|보고서|조사|추천해|전망|원인|이유|analy[sz]e|compare|report|research",
        RegexOption.IGNORE_CASE,
    )
    private val agentCue = Regex(
        "기획|계획\\s?(을|를)?\\s?(세워|짜)|설계|프로젝트|코드|코딩|프로그램|스크립트|함수|앱\\s?(을|를)?\\s?만들|자동화|" +
            "작성해|써\\s?줘|만들어\\s?줘|만들어|구현|리팩터|디버그|버그|문서|대본|시나리오|스토리보드|자막|영상|기사|보고서|제안서|" +
            "단계별|순서대로|정리해\\s?줘|python|파이썬|kotlin|코틀린|javascript|code|project|script|build|implement|write",
        RegexOption.IGNORE_CASE,
    )
    private val multiStep = Regex("그리고\\s.+(해|줘)|다음에|그\\s?다음|먼저.+(그리고|다음)|then\\s", RegexOption.IGNORE_CASE)

    fun classify(input: String): Classification {
        val text = wakeWord.replace(input, "").trim()
        if (text.isEmpty()) return Classification(Route.CHAT, "빈 입력")

        // 1) 기기 기능: "일정 추가", "알람 7시에 맞춰" — 단, "여행 일정 계획 세워줘"는 기획(AGENT)
        if (deviceObject.containsMatchIn(text) && deviceAction.containsMatchIn(text) && !planning.containsMatchIn(text)) {
            return Classification(Route.DEVICE, "기기 기능 키워드")
        }

        val wantsSearch = searchCue.containsMatchIn(text)
        val wantsAnalysis = analysisCue.containsMatchIn(text)
        val wantsAgent = agentCue.containsMatchIn(text)

        // 2) 검색 + 분석 → Gemini 검색 분석
        if (wantsSearch && wantsAnalysis) return Classification(Route.RESEARCH, "검색 + 분석 요청")
        // 3) 복잡한 작업 / 다단계 / 긴 지시 → NVIDIA 에이전트 모델
        if (wantsAgent) return Classification(Route.AGENT, "작업·제작 요청")
        if (multiStep.containsMatchIn(text)) return Classification(Route.AGENT, "다단계 명령")
        if (text.length > 160) return Classification(Route.AGENT, "긴 지시문")
        if (wantsAnalysis) return Classification(Route.AGENT, "분석 요청")
        // 4) 최신 정보 → Google 검색
        if (wantsSearch) return Classification(Route.SEARCH, "최신 정보·검색 요청")
        // 5) 나머지 → 일반 대화 (가장 저렴)
        return Classification(Route.CHAT, "일반 대화")
    }
}
