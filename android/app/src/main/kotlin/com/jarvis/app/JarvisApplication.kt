package com.jarvis.app

import android.app.Application
import com.jarvis.app.data.DataStoreSettingsStore
import com.jarvis.app.data.KeystoreTokenStore
import com.jarvis.app.data.SettingsStore
import com.jarvis.app.data.TokenStore
import com.jarvis.core.network.HttpTransport
import com.jarvis.core.network.OkHttpSseTransport
import com.jarvis.core.network.OkHttpTransport
import com.jarvis.core.network.SseTransport

/** 앱 전역 의존성 (간단한 수동 DI). */
class AppContainer(
    val settings: SettingsStore,
    val tokens: TokenStore,
    val transport: HttpTransport,
    val sse: SseTransport,
)

class JarvisApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(
            settings = DataStoreSettingsStore(this),
            tokens = KeystoreTokenStore(this),
            transport = OkHttpTransport(),
            sse = OkHttpSseTransport(),
        )
    }
}
