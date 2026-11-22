package com.jarvis.core.voice

/** 음성 설정. */
data class VoiceSettings(
    /** 음성 인식 언어 (BCP-47). ko-KR / en-US */
    val language: String = "ko-KR",
    /** 음성으로 요청하면 답변을 소리 내어 읽는다 */
    val speakReplies: Boolean = true,
    /** 답변을 읽은 뒤 자동으로 다시 듣는다 (음성 대화 모드) */
    val continuousConversation: Boolean = false,
    /** 앱이 화면에 켜져 있는 동안 "자비스" 호출을 기다린다 (마이크 상시 사용) */
    val wakeWordEnabled: Boolean = false,
    /** TTS 말하기 속도 (0.5 ~ 2.0, 1.0 = 보통) */
    val speechRate: Float = 1.0f,
    /** TTS 음량 (0.0 ~ 1.0) */
    val volume: Float = 1.0f,
) {
    fun normalized(): VoiceSettings = copy(
        language = if (language in SUPPORTED_LANGUAGES) language else "ko-KR",
        speechRate = speechRate.coerceIn(0.5f, 2.0f),
        volume = volume.coerceIn(0f, 1f),
    )

    companion object {
        val SUPPORTED_LANGUAGES = listOf("ko-KR", "en-US")
    }
}
