package com.jarvis.app.voice

import com.jarvis.core.voice.WakeWord
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Vosk 오프라인 음성 인식으로 "자비스" 호출어를 감지한다.
 *
 * 인터넷 없이 기기 안에서만 처리하며, 들은 소리는 저장하거나 전송하지 않는다.
 * 인식 결과 문장에서 [WakeWord.matchesLoosely]로 "자비스"(및 비슷한 발음)를 찾는다.
 */
class VoskHotwordDetector(private val models: HotwordModelManager) : HotwordDetector {

    override suspend fun awaitWakeWord() {
        val model = models.model()
        suspendCancellableCoroutine { cont ->
            val finished = AtomicBoolean(false)
            val recognizer = Recognizer(model, SAMPLE_RATE)
            val service = SpeechService(recognizer, SAMPLE_RATE)

            fun release() {
                runCatching { service.stop() }
                runCatching { service.shutdown() }
                runCatching { recognizer.close() }
            }

            fun finish(error: Throwable? = null) {
                if (!finished.compareAndSet(false, true)) return
                release()
                if (error == null) cont.resume(Unit) else cont.resumeWithException(error)
            }

            fun check(hypothesis: String?, key: String) {
                val text = hypothesis?.let { runCatching { JSONObject(it).optString(key) }.getOrNull() }.orEmpty()
                if (text.isNotBlank() && WakeWord.matchesLoosely(text)) finish()
            }

            service.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) = check(hypothesis, "partial")
                override fun onResult(hypothesis: String?) = check(hypothesis, "text")
                override fun onFinalResult(hypothesis: String?) = check(hypothesis, "text")
                override fun onError(exception: Exception?) =
                    finish(HotwordUnavailableException("호출어 인식 오류: ${exception?.message ?: "알 수 없음"}"))
                override fun onTimeout() = Unit
            })

            cont.invokeOnCancellation {
                if (finished.compareAndSet(false, true)) release()
            }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16000f
    }
}
