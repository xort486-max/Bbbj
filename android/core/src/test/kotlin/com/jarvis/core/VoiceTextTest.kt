package com.jarvis.core

import com.jarvis.core.voice.SpeechText
import com.jarvis.core.voice.VoiceSettings
import com.jarvis.core.voice.WakeWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WakeWordTest {
    @Test
    fun detectsWakeWordAndExtractsCommand() {
        assertEquals("오늘 날씨 알려줘", WakeWord.detect("자비스 오늘 날씨 알려줘")?.command)
        assertEquals("오늘 날씨 알려줘", WakeWord.detect("자비스야, 오늘 날씨 알려줘")?.command)
        assertEquals("일정 알려줘", WakeWord.detect("헤이 자비스 일정 알려줘")?.command)
        assertEquals("what time is it", WakeWord.detect("Jarvis, what time is it")?.command)
        assertEquals("뉴스 틀어줘", WakeWord.detect("자 비스 뉴스 틀어줘")?.command)
        assertEquals("음악", WakeWord.detect("음 쟈비스 음악")?.command)
    }

    @Test
    fun wakeWordAloneHasNoCommand() {
        val detection = WakeWord.detect("자비스")
        assertEquals("", detection?.command)
        assertFalse(detection!!.hasCommand)
        assertFalse(WakeWord.detect("자비스야!")!!.hasCommand)
    }

    @Test
    fun ignoresSpeechWithoutWakeWord() {
        assertNull(WakeWord.detect("오늘 날씨 알려줘"))
        assertNull(WakeWord.detect("자비로운 스님"))
        assertNull(WakeWord.detect(""))
    }
}

class LooseWakeWordTest {
    @Test
    fun acceptsOfflineModelMishearings() {
        // 실제 Vosk 한국어 소형 모델이 합성 음성 "자비스…"를 받아 적은 결과 (2026-11-22 시험)
        listOf("저 비스 오류 하오", "자 비즈", "음악 어디 요 자 미쓰 여", "자비스", "자 비스 오늘").forEach {
            assertTrue(WakeWord.matchesLoosely(it), it)
        }
    }

    @Test
    fun rejectsOrdinarySpeech() {
        listOf("오후 [9] 시 가 어두워", "저 비싸", "자비로운", "제비 스스로", "", "오늘 날씨 어때").forEach {
            assertFalse(WakeWord.matchesLoosely(it), it)
        }
    }
}

class SpeechTextTest {
    @Test
    fun stripsMarkdownAndUrls() {
        val text = """
            ## 결론
            **스페인**이 우승했습니다. 자세한 내용은 [UEFA](https://uefa.com) 참고.
            - 첫째: `requests` 사용
            > 인용문
            출처 https://example.com/a?b=1
        """.trimIndent()
        val spoken = SpeechText.toSpeakable(text)
        assertTrue(spoken.startsWith("결론"), spoken)
        assertTrue("스페인이 우승했습니다." in spoken, spoken)
        assertTrue("UEFA 참고" in spoken, spoken)
        assertTrue("첫째: requests 사용" in spoken, spoken)
        listOf("**", "##", "`", "http", ">", "- ").forEach { assertFalse(it in spoken, "'$it' in $spoken") }
    }

    @Test
    fun replacesCodeBlocksWithNotice() {
        val spoken = SpeechText.toSpeakable("예시입니다.\n```python\nprint('hi')\n```\n끝.")
        assertFalse("print" in spoken)
        assertTrue(spoken.endsWith("코드는 화면에서 확인하세요."), spoken)
    }

    @Test
    fun truncatesLongAnswersAtSentence() {
        val long = (1..60).joinToString(" ") { "문장 $it 입니다." }
        val spoken = SpeechText.toSpeakable(long, maxChars = 100)
        assertTrue(spoken.endsWith("나머지는 화면에서 확인하세요."), spoken)
        assertTrue(spoken.length < 140, "${spoken.length}")
        assertTrue(spoken.substringBefore(" 나머지").endsWith("."), spoken)
    }

    @Test
    fun settingsAreClamped() {
        val s = VoiceSettings(language = "fr-FR", speechRate = 5f, volume = -1f).normalized()
        assertEquals(Triple("ko-KR", 2f, 0f), Triple(s.language, s.speechRate, s.volume))
    }
}
