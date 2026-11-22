package com.jarvis.app.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jarvis.app.JarvisApplication
import com.jarvis.app.MainActivity
import com.jarvis.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

/**
 * "자비스" 호출어 상시 대기 서비스 (앱이 꺼져 있어도 동작).
 *
 * Android 규칙에 따라:
 *  - 마이크(microphone) 유형 포그라운드 서비스 + 상시 알림으로만 동작한다 (알림에서 바로 끌 수 있음).
 *  - 앱이 화면에 보일 때만 시작할 수 있다. 시작된 뒤에는 앱을 닫아도 계속 듣는다.
 *  - 휴대폰을 재부팅하면 자동으로 다시 켜지지 않는다 (Android 14+는 부팅 시 마이크 서비스 시작 금지) → 앱을 한 번 열면 다시 켜진다.
 *
 * 호출어 감지는 기기 안(Vosk 오프라인 모델)에서만 처리하며, 호출 뒤의 명령만 음성 인식·백엔드로 보낸다.
 */
class JarvisVoiceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val container = (application as JarvisApplication).container
            container.controller.updateVoiceSettings { it.copy(wakeWordEnabled = false) }
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        } catch (e: Exception) {
            // 백그라운드에서 시작 시도 등 Android가 허용하지 않는 경우
            (application as JarvisApplication).container.controller.setWakeServiceRunning(false)
            stopSelf()
            return START_NOT_STICKY
        }
        startListeningLoop()
        return START_STICKY
    }

    private var loopStarted = false

    private fun startListeningLoop() {
        if (loopStarted) return
        loopStarted = true
        val container = (application as JarvisApplication).container
        val controller = container.controller
        controller.setWakeServiceRunning(true)

        scope.launch {
            // 음성 세션이 진행 중이면 마이크를 놓고, 끝나면 다시 호출어를 기다린다.
            controller.voiceActive.collectLatest { active ->
                if (active) return@collectLatest
                try {
                    while (true) {
                        container.hotwordDetector.awaitWakeWord()
                        // JARVIS가 기본 어시스턴트면 시리처럼 화면 위에 어시스턴트 창을 띄운다.
                        if (com.jarvis.app.assist.JarvisInteractionService.showOverlay(greet = true)) {
                            val started = withTimeoutOrNull(5_000) { controller.voiceActive.first { it } }
                            if (started != null) break else continue
                        }
                        // 아니면 화면 없이 음성으로만 응답한다.
                        if (controller.onWakeWordDetected()) break
                    }
                } catch (e: HotwordUnavailableException) {
                    controller.updateVoiceSettings { it.copy(wakeWordEnabled = false) }
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        (application as JarvisApplication).container.controller.setWakeServiceRunning(false)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "자비스 호출 대기", NotificationManager.IMPORTANCE_LOW).apply {
                description = "앱이 꺼져 있어도 \"자비스\" 호출을 기다리는 동안 표시됩니다."
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, JarvisVoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("\"자비스\" 호출 대기 중")
            .setContentText("마이크 사용 중 · 소리는 기기 안에서만 처리됩니다")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "끄기", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_wake_word"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.jarvis.app.action.STOP_WAKE_WORD"

        /** 앱 화면이 보이는 동안에만 호출해야 한다 (Android 14+ 규칙). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, JarvisVoiceService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisVoiceService::class.java))
        }
    }
}
