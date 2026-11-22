package com.jarvis.core.voice

/**
 * 웨이크 워드("자비스") 감지.
 *
 * 음성 인식 결과 문장에서 호출어를 찾고, 호출어 뒤의 명령을 떼어 낸다.
 * 인식기가 "자 비스", "쟈비스", "자비쓰", "Jarvis" 등으로 받아 적는 경우도 허용한다.
 */
object WakeWord {
    private val pattern = Regex(
        "(?:^|[\\s,.!?~])(?:헤이\\s*|hey\\s+|오케이\\s*|ok\\s+)?(자\\s?비\\s?스|쟈\\s?비\\s?스|자\\s?비\\s?쓰|자비즈|jarvis|jervis)(?:야|아|님|씨)?(?=$|[\\s,.!?~])[\\s,.!?~]*",
        RegexOption.IGNORE_CASE,
    )

    /** 호출어를 찾으면 그 뒤의 명령(없으면 빈 문자열)을 돌려준다. 호출어가 없으면 null. */
    fun detect(transcript: String): Detection? {
        val match = pattern.find(transcript) ?: return null
        val command = transcript.substring(match.range.last + 1).trim().trimStart(',', '.', '!', '?', '~').trim()
        return Detection(command)
    }

    /**
     * 오프라인 호출어 감지기(Vosk)용 느슨한 판정.
     *
     * 소형 오프라인 모델은 "자비스"를 "저 비스", "자 비즈", "자 미쓰"처럼 비슷한 발음으로 받아 적는 경우가 많다
     * (합성 음성 시험에서 확인). 그래서 첫 음절(자/저/재/쟈/제/차) + 둘째(비/미/피) + 셋째(스/즈/쓰/시/씨)
     * 조합을 호출로 본다. 일반 대화에서 이런 3음절 조합이 우연히 나올 확률은 낮다.
     */
    fun matchesLoosely(transcript: String): Boolean =
        loosePattern.containsMatchIn(transcript) || detect(transcript) != null

    private val loosePattern = Regex(
        "(?:^|\\s)(자|저|재|쟈|제|차)\\s?(비|미|피)\\s?(스|즈|쓰|시|씨)(?:야|아)?(?=$|\\s)",
    )

    data class Detection(val command: String) {
        val hasCommand: Boolean get() = command.isNotBlank()
    }
}
