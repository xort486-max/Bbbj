package com.jarvis.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.jarvis.core.voice.VoiceSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/** Android TextToSpeech 기반 음성 출력. 엔진 초기화는 처음 말할 때 한 번 한다. */
class AndroidVoiceOutput(private val context: Context) : VoiceOutput {
    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null

    private suspend fun engine(): TextToSpeech? {
        ready?.let { return if (it.await()) tts else null }
        val deferred = CompletableDeferred<Boolean>().also { ready = it }
        tts = TextToSpeech(context.applicationContext) { status -> deferred.complete(status == TextToSpeech.SUCCESS) }
        val ok = deferred.await()
        if (ok) {
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
        } else {
            ready = null // 다음에 다시 초기화 시도
        }
        return if (ok) tts else null
    }

    override suspend fun speak(text: String, settings: VoiceSettings): Result<Unit> {
        if (text.isBlank()) return Result.success(Unit)
        val engine = engine() ?: return Result.failure(IllegalStateException("음성 출력(TTS) 엔진을 사용할 수 없습니다."))

        val locale = Locale.forLanguageTag(settings.language)
        val langResult = engine.setLanguage(locale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            return Result.failure(
                IllegalStateException("${locale.displayLanguage} 음성 데이터가 없습니다. 휴대폰 설정 > 텍스트 음성 변환에서 설치하세요."),
            )
        }
        engine.setSpeechRate(settings.speechRate)

        val id = UUID.randomUUID().toString()
        return suspendCancellableCoroutine { cont ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Result.success(Unit))
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (utteranceId == id && cont.isActive) cont.resume(Result.success(Unit))
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Result.failure(IllegalStateException("음성 출력 중 오류가 발생했습니다.")))
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == id && cont.isActive) cont.resume(Result.failure(IllegalStateException("음성 출력 오류($errorCode)")))
                }
            })
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, settings.volume) }
            val queued = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
            if (queued != TextToSpeech.SUCCESS && cont.isActive) {
                cont.resume(Result.failure(IllegalStateException("음성 출력을 시작하지 못했습니다.")))
            }
            cont.invokeOnCancellation { engine.stop() }
        }
    }

    override fun stop() {
        tts?.stop()
    }
}
