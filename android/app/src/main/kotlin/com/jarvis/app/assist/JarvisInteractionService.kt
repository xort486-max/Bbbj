package com.jarvis.app.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * JARVIS를 Android "기본 디지털 어시스턴트"로 등록하기 위한 서비스.
 *
 * 사용자가 설정 > 기본 앱 > 디지털 어시스턴트 앱에서 JARVIS를 고르면, 시스템이 이 서비스를 상시 연결(bind)하고
 * 측면 버튼 길게 누르기 · 홈 버튼 길게 누르기 · 모서리 스와이프 같은 어시스턴트 호출 동작이 JARVIS를 연다.
 */
class JarvisInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        current = this
    }

    override fun onShutdown() {
        if (current === this) current = null
        super.onShutdown()
    }

    companion object {
        /** JARVIS가 기본 어시스턴트로 지정되어 시스템에 연결되어 있을 때만 non-null */
        @Volatile
        var current: JarvisInteractionService? = null
            private set

        /**
         * 어시스턴트 화면을 띄운다 (호출어 감지 시). 기본 어시스턴트일 때만 가능하며,
         * 이 경로는 앱이 백그라운드에 있어도 다른 앱 위에 화면을 띄울 수 있다.
         */
        fun showOverlay(greet: Boolean): Boolean {
            val service = current ?: return false
            return runCatching {
                service.showSession(Bundle().apply { putBoolean(AssistActivity.EXTRA_GREET, greet) }, 0)
                true
            }.getOrDefault(false)
        }
    }
}

/** 어시스턴트 세션 생성 서비스. */
class JarvisSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = JarvisSession(this)
}

/**
 * 어시스턴트 세션: 호출되면 JARVIS 오버레이 화면(AssistActivity)을 띄운다.
 * 다른 앱 화면 내용(스크린샷·구조)은 읽지 않는다 — 요청하지 않음.
 */
class JarvisSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, AssistActivity::class.java)
            .putExtra(AssistActivity.EXTRA_GREET, args?.getBoolean(AssistActivity.EXTRA_GREET) ?: false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startAssistantActivity(intent)
        hide()
    }
}
