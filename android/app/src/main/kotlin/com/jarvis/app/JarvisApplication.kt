package com.jarvis.app

import android.app.Application
import com.jarvis.app.assistant.JarvisController
import com.jarvis.app.data.DataStoreSettingsStore
import com.jarvis.app.data.KeystoreTokenStore
import com.jarvis.app.data.SettingsStore
import com.jarvis.app.data.TokenStore
import com.jarvis.app.voice.AndroidVoiceInput
import com.jarvis.app.voice.AndroidVoiceOutput
import com.jarvis.app.voice.HotwordDetector
import com.jarvis.app.voice.HotwordModelManager
import com.jarvis.app.voice.VoskHotwordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 앱 전역 의존성 (간단한 수동 DI). 앱 프로세스가 살아 있는 동안 하나씩만 존재한다. */
class AppContainer(
    val settings: SettingsStore,
    val tokens: TokenStore,
    val controller: JarvisController,
    val hotwordModels: HotwordModelManager,
    val hotwordDetector: HotwordDetector,
)

class JarvisApplication : Application() {
    lateinit var container: AppContainer
        private set

    /** 화면이 닫혀도 유지되는 앱 범위 코루틴 (백그라운드 음성 서비스가 사용) */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val settings = DataStoreSettingsStore(this)
        val tokens = KeystoreTokenStore(this)
        val models = HotwordModelManager(this)
        container = AppContainer(
            settings = settings,
            tokens = tokens,
            controller = JarvisController(
                scope = applicationScope,
                settings = settings,
                tokens = tokens,
                transport = com.jarvis.core.network.OkHttpTransport(),
                sseTransport = com.jarvis.core.network.OkHttpSseTransport(),
                voiceInput = AndroidVoiceInput(this),
                voiceOutput = AndroidVoiceOutput(this),
            ),
            hotwordModels = models,
            hotwordDetector = VoskHotwordDetector(models),
        )
    }
}
