package com.jarvis.app.voice

import com.jarvis.core.voice.VoiceSettings
import kotlinx.coroutines.flow.Flow

/** 음성 인식 이벤트. */
sealed interface VoiceEvent {
    /** 인식기가 말을 들을 준비가 됨 */
    data object Ready : VoiceEvent

    /** 말하는 도중의 중간 인식 결과 */
    data class Partial(val text: String) : VoiceEvent

    /** 마이크 입력 음량 (0..1) — 파형 표시용 */
    data class Level(val value: Float) : VoiceEvent

    /** 최종 인식 결과 (스트림 종료) */
    data class Final(val text: String) : VoiceEvent

    /** 인식 실패 (스트림 종료). silence=true면 말소리가 없었던 경우 */
    data class Failure(val code: Int, val message: String, val silence: Boolean) : VoiceEvent
}

/** 음성 인식(STT) 추상화 — 앱은 Android SpeechRecognizer, 테스트는 가짜 구현. */
interface VoiceInput {
    fun isAvailable(): Boolean

    /** 한 번 듣고 Final 또는 Failure를 내보낸 뒤 끝난다. 수집을 취소하면 즉시 듣기를 멈춘다. */
    fun listen(language: String): Flow<VoiceEvent>
}

/** 음성 출력(TTS) 추상화. */
interface VoiceOutput {
    /** 다 읽을 때까지(또는 stop() 될 때까지) 기다린다. */
    suspend fun speak(text: String, settings: VoiceSettings): Result<Unit>

    fun stop()
}

/** 호출어("자비스") 감지기 — 앱이 꺼져 있어도 포그라운드 서비스에서 동작한다. */
interface HotwordDetector {
    /** 호출어가 들릴 때까지 기다린다. 취소하면 마이크를 놓는다. */
    suspend fun awaitWakeWord()
}

class HotwordUnavailableException(message: String) : Exception(message)
