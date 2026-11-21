package com.jarvis.core.network

import java.net.URI
import java.net.URISyntaxException

/**
 * JARVIS 백엔드 접속 정보.
 *
 * 앱은 AI 제공자 API Key를 갖지 않는다. 앱이 아는 것은 백엔드 주소와
 * 사용자 인증 토큰(Android Keystore로 암호화 보관)뿐이다.
 */
data class BackendConfiguration(
    /** 끝에 "/"가 없는 기본 주소 (예: https://jarvis.example.com) */
    val baseUrl: String,
    val timeoutMillis: Long = 30_000,
) {
    fun url(path: String): String = baseUrl + "/" + path.removePrefix("/")

    sealed class ValidationError(message: String) : IllegalArgumentException(message) {
        class Empty : ValidationError("백엔드 주소가 설정되지 않았습니다.")
        class InvalidUrl : ValidationError("백엔드 주소 형식이 올바르지 않습니다.")
        class InsecureScheme :
            ValidationError("백엔드 주소는 HTTPS여야 합니다. (개발용 localhost/사설망 주소만 HTTP 허용)")
    }

    companion object {
        /**
         * 문자열 주소를 검증해 설정을 만든다.
         *
         * HTTPS만 허용한다. 단, 개발 중 PC에서 실행하는 로컬 백엔드를 위해
         * localhost, 127.0.0.1, 10.0.2.2(에뮬레이터 → PC), *.local, 사설 IPv4 대역은 HTTP를 허용한다.
         */
        fun validated(raw: String, timeoutMillis: Long = 30_000): BackendConfiguration {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) throw ValidationError.Empty()
            val uri = try {
                URI(trimmed)
            } catch (_: URISyntaxException) {
                throw ValidationError.InvalidUrl()
            }
            val scheme = uri.scheme?.lowercase() ?: throw ValidationError.InvalidUrl()
            val host = uri.host?.takeIf { it.isNotEmpty() } ?: throw ValidationError.InvalidUrl()
            when (scheme) {
                "https" -> Unit
                "http" -> if (!isLocalDevelopmentHost(host)) throw ValidationError.InsecureScheme()
                else -> throw ValidationError.InvalidUrl()
            }
            return BackendConfiguration(trimmed.trimEnd('/'), timeoutMillis)
        }

        fun isLocalDevelopmentHost(rawHost: String): Boolean {
            val host = rawHost.lowercase().removePrefix("[").removeSuffix("]")
            if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".local")) return true
            val octets = host.split(".").map { it.toIntOrNull() ?: return false }
            if (octets.size != 4 || octets.any { it !in 0..255 }) return false
            return when {
                octets[0] == 10 -> true
                octets[0] == 192 && octets[1] == 168 -> true
                octets[0] == 172 && octets[1] in 16..31 -> true
                else -> false
            }
        }
    }
}
