package com.jarvis.app.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.speech.RecognitionService

/**
 * 실제 음성 인식을 맡길 외부 인식 서비스(보통 Google)를 고른다.
 *
 * JARVIS를 기본 디지털 어시스턴트로 지정하면 Android가 "음성 입력" 기본값을 JARVIS의 인식 서비스로 바꿀 수 있다.
 * 그 경우 JARVIS 자신을 다시 부르는 순환을 막기 위해, 우리 패키지를 제외한 인식 서비스를 직접 지정해 쓴다.
 */
object RecognizerPicker {
    private const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
    private const val GOOGLE_TTS = "com.google.android.tts"

    fun external(context: Context): ComponentName? {
        val candidates = context.packageManager
            .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != context.packageName }
        return candidates.firstOrNull { it.packageName == GOOGLE_APP }
            ?: candidates.firstOrNull { it.packageName == GOOGLE_TTS }
            ?: candidates.firstOrNull { it.packageName.contains("google") }
            ?: candidates.firstOrNull()
    }
}
