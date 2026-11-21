package com.jarvis.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.jarvis.core.network.AuthTokenProvider
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 백엔드 앱 인증 토큰 저장소.
 *
 * AI 제공자(NVIDIA/Google) API Key는 앱에 저장하지 않는다. 여기 저장되는 것은
 * JARVIS 백엔드의 JARVIS_APP_TOKEN 값뿐이다.
 */
interface TokenStore : AuthTokenProvider {
    fun hasToken(): Boolean = !currentToken().isNullOrBlank()
    fun save(token: String)
    fun delete()
}

/**
 * Android Keystore의 AES-256-GCM 키로 토큰을 암호화해 SharedPreferences에 보관한다.
 * 키는 하드웨어 보안 영역(지원 기기)에 있고 기기 밖으로 내보낼 수 없다.
 */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences("jarvis_secure", Context.MODE_PRIVATE)

    override fun currentToken(): String? {
        val stored = prefs.getString(PREF_TOKEN, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_LENGTH)
            val cipherText = bytes.copyOfRange(IV_LENGTH, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrNull() // 키가 무효화된 경우(기기 복원 등) 토큰을 다시 입력하도록 null 반환
    }

    override fun save(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit { putString(PREF_TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP)) }
    }

    override fun delete() {
        prefs.edit { remove(PREF_TOKEN) }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "jarvis_app_token_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREF_TOKEN = "app_token"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
