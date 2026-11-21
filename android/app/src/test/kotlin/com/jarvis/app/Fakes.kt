package com.jarvis.app

import com.jarvis.app.data.SettingsStore
import com.jarvis.app.data.TokenStore
import com.jarvis.core.network.HttpResponse
import com.jarvis.core.network.HttpTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeSettingsStore(initialUrl: String = "") : SettingsStore {
    private val url = MutableStateFlow(initialUrl)
    override val backendUrl: StateFlow<String> = url
    override suspend fun setBackendUrl(url: String) {
        this.url.value = url.trim()
    }
}

class FakeTokenStore(private var token: String? = null) : TokenStore {
    override fun currentToken(): String? = token
    override fun save(token: String) {
        this.token = token
    }
    override fun delete() {
        token = null
    }
}

/** 경로별 고정 응답. 등록되지 않은 경로는 404. */
class StubTransport(private val routes: Map<String, Pair<Int, String>> = emptyMap()) : HttpTransport {
    val requestedUrls = mutableListOf<String>()
    override suspend fun send(request: com.jarvis.core.network.HttpRequest): HttpResponse {
        requestedUrls += request.url
        val path = java.net.URI(request.url).path
        val (status, body) = routes[path] ?: (404 to """{"detail":"not found"}""")
        return HttpResponse(status, body)
    }
}
