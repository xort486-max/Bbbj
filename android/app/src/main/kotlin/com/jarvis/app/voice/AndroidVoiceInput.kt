package com.jarvis.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/**
 * Android SpeechRecognizer 기반 음성 인식.
 *
 * - Android 12+ 에서 기기 내 인식(on-device)이 가능하면 우선 사용한다 (네트워크·지연 감소, 음성이 기기 밖으로 안 나감).
 * - 기기 내 인식기에 해당 언어 팩이 없으면 기본(Google) 인식기로 자동 전환한다.
 */
class AndroidVoiceInput(private val context: Context) : VoiceInput {

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override fun listen(language: String): Flow<VoiceEvent> = callbackFlow {
        var recognizer: SpeechRecognizer? = null
        var triedOnDevice = false

        fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        fun start(onDevice: Boolean) {
            recognizer?.destroy()
            val created = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            recognizer = created
            created.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    trySend(VoiceEvent.Ready)
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // 대략 -2 ~ 10 dB 범위를 0..1로 정규화
                    trySend(VoiceEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    firstResult(partialResults)?.let { trySend(VoiceEvent.Partial(it)) }
                }

                override fun onResults(results: Bundle?) {
                    val text = firstResult(results).orEmpty()
                    if (text.isBlank()) {
                        trySend(VoiceEvent.Failure(SpeechRecognizer.ERROR_NO_MATCH, "말씀을 알아듣지 못했어요.", silence = true))
                    } else {
                        trySend(VoiceEvent.Final(text))
                    }
                    close()
                }

                override fun onError(error: Int) {
                    val languageMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
                    if (triedOnDevice && languageMissing) {
                        // 기기 내 인식기에 한국어 팩이 없음 → 기본 인식기로 다시 시도
                        triedOnDevice = false
                        start(onDevice = false)
                        recognizer?.startListening(intent())
                        return
                    }
                    trySend(failure(error))
                    close()
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }

        val onDeviceAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        triedOnDevice = onDeviceAvailable
        start(onDevice = onDeviceAvailable)
        recognizer?.startListening(intent())

        awaitClose {
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
        }
    }.flowOn(Dispatchers.Main.immediate) // SpeechRecognizer는 메인 스레드에서만 사용 가능

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun failure(error: Int): VoiceEvent.Failure {
        val (message, silence) = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "말씀을 알아듣지 못했어요." to true
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "음성이 들리지 않았어요." to true
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 없습니다. 설정에서 허용해 주세요." to false
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "음성 인식 서버에 연결할 수 없습니다. 인터넷 연결을 확인하세요." to false
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "음성 인식기가 사용 중입니다. 잠시 후 다시 시도하세요." to false
            SpeechRecognizer.ERROR_AUDIO -> "마이크 녹음 오류가 발생했습니다." to false
            SpeechRecognizer.ERROR_SERVER -> "음성 인식 서버 오류입니다." to false
            else -> "음성 인식 오류($error)가 발생했습니다." to false
        }
        return VoiceEvent.Failure(error, message, silence)
    }
}
