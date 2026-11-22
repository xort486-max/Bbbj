package com.jarvis.app.assist

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.jarvis.app.voice.RecognizerPicker

/**
 * 어시스턴트 등록에 필요한 음성 인식 서비스.
 *
 * JARVIS를 기본 어시스턴트로 고르면 휴대폰의 "음성 입력" 기본값도 이 서비스로 바뀔 수 있다.
 * 다른 앱의 음성 입력이 망가지지 않도록, 받은 요청을 그대로 Google 등 실제 인식 서비스에 전달(중계)한다.
 */
class JarvisRecognitionService : RecognitionService() {
    private var delegate: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        val target = RecognizerPicker.external(this)
        if (target == null) {
            runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        delegate?.destroy()
        delegate = SpeechRecognizer.createSpeechRecognizer(this, target).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { runCatching { listener.readyForSpeech(params ?: Bundle()) } }
                override fun onBeginningOfSpeech() { runCatching { listener.beginningOfSpeech() } }
                override fun onRmsChanged(rmsdB: Float) { runCatching { listener.rmsChanged(rmsdB) } }
                override fun onBufferReceived(buffer: ByteArray?) { buffer?.let { runCatching { listener.bufferReceived(it) } } }
                override fun onEndOfSpeech() { runCatching { listener.endOfSpeech() } }
                override fun onError(error: Int) { runCatching { listener.error(error) } }
                override fun onResults(results: Bundle?) { runCatching { listener.results(results ?: Bundle()) } }
                override fun onPartialResults(partialResults: Bundle?) { runCatching { listener.partialResults(partialResults ?: Bundle()) } }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            startListening(recognizerIntent)
        }
    }

    override fun onStopListening(listener: Callback) {
        delegate?.stopListening()
    }

    override fun onCancel(listener: Callback) {
        delegate?.cancel()
    }

    override fun onDestroy() {
        delegate?.destroy()
        delegate = null
        super.onDestroy()
    }
}
