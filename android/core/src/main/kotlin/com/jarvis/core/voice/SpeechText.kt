package com.jarvis.core.voice

/**
 * 화면용 답변 텍스트를 음성(TTS)으로 읽기 좋게 바꾼다.
 *
 * - 코드 블록은 읽지 않고 "코드는 화면에서 확인하세요"로 대신한다.
 * - 마크다운 기호(**, #, `, >, 표 구분선)와 URL을 제거한다.
 * - 너무 긴 답변은 문장 단위로 잘라 앞부분만 읽고 나머지는 화면을 보도록 안내한다.
 *
 * 화면에 표시되는 원문은 바꾸지 않는다 (읽기 전용 변환).
 */
object SpeechText {
    const val DEFAULT_MAX_CHARS = 500
    private const val CODE_NOTICE = "코드는 화면에서 확인하세요."
    private const val MORE_NOTICE = "나머지는 화면에서 확인하세요."

    private val codeBlock = Regex("```[\\s\\S]*?(```|$)")
    private val inlineCode = Regex("`([^`]*)`")
    private val url = Regex("https?://\\S+")
    private val markdownLink = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val heading = Regex("(?m)^\\s{0,3}#{1,6}\\s*")
    private val quote = Regex("(?m)^\\s*>\\s?")
    private val tableRule = Regex("(?m)^\\s*\\|?\\s*:?-{3,}.*$")
    private val emphasis = Regex("(\\*\\*|__|\\*|~~)")
    private val bullet = Regex("(?m)^\\s*[-*•]\\s+")
    private val pipes = Regex("\\s*\\|\\s*")
    private val spaces = Regex("[ \\t]+")
    private val blankLines = Regex("\\n{2,}")

    fun toSpeakable(text: String, maxChars: Int = DEFAULT_MAX_CHARS): String {
        var s = text
        var hadCode = false
        s = codeBlock.replace(s) { hadCode = true; " " }
        s = markdownLink.replace(s) { it.groupValues[1] }
        s = url.replace(s, "")
        s = inlineCode.replace(s) { it.groupValues[1] }
        s = tableRule.replace(s, "")
        s = heading.replace(s, "")
        s = quote.replace(s, "")
        s = bullet.replace(s, "")
        s = emphasis.replace(s, "")
        s = pipes.replace(s, ", ")
        s = spaces.replace(s, " ")
        s = blankLines.replace(s, "\n").trim()

        val truncated = s.length > maxChars
        if (truncated) s = cutAtSentence(s, maxChars)
        val notices = listOfNotNull(CODE_NOTICE.takeIf { hadCode }, MORE_NOTICE.takeIf { truncated })
        return (listOf(s) + notices).filter { it.isNotBlank() }.joinToString(" ").trim()
    }

    private fun cutAtSentence(s: String, maxChars: Int): String {
        val head = s.take(maxChars)
        val end = head.indexOfLast { it == '.' || it == '?' || it == '!' || it == '다' && head.getOrNull(head.indexOf(it) + 1) == '.' || it == '\n' }
        val cut = if (end >= maxChars / 2) head.take(end + 1) else head
        return cut.trimEnd(',', ' ', '\n')
    }
}
