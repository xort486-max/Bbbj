package com.jarvis.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jarvis.core.voice.VoiceSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 비밀이 아닌 앱 설정. 비밀값(토큰)은 [TokenStore]에 저장한다. */
interface SettingsStore {
    val backendUrl: Flow<String>
    suspend fun currentBackendUrl(): String = backendUrl.first()
    suspend fun setBackendUrl(url: String)

    val voiceSettings: Flow<VoiceSettings>
    suspend fun setVoiceSettings(settings: VoiceSettings)
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "jarvis_settings")

class DataStoreSettingsStore(private val context: Context) : SettingsStore {
    private val backendUrlKey = stringPreferencesKey("backend_url")
    private val languageKey = stringPreferencesKey("voice_language")
    private val speakRepliesKey = booleanPreferencesKey("voice_speak_replies")
    private val continuousKey = booleanPreferencesKey("voice_continuous")
    private val wakeKey = booleanPreferencesKey("voice_wake_word")
    private val rateKey = floatPreferencesKey("voice_rate")
    private val volumeKey = floatPreferencesKey("voice_volume")

    override val backendUrl: Flow<String> = context.dataStore.data.map { it[backendUrlKey].orEmpty() }

    override suspend fun setBackendUrl(url: String) {
        context.dataStore.edit { it[backendUrlKey] = url.trim() }
    }

    override val voiceSettings: Flow<VoiceSettings> = context.dataStore.data.map { p ->
        val d = VoiceSettings()
        VoiceSettings(
            language = p[languageKey] ?: d.language,
            speakReplies = p[speakRepliesKey] ?: d.speakReplies,
            continuousConversation = p[continuousKey] ?: d.continuousConversation,
            wakeWordEnabled = p[wakeKey] ?: d.wakeWordEnabled,
            speechRate = p[rateKey] ?: d.speechRate,
            volume = p[volumeKey] ?: d.volume,
        ).normalized()
    }

    override suspend fun setVoiceSettings(settings: VoiceSettings) {
        val s = settings.normalized()
        context.dataStore.edit {
            it[languageKey] = s.language
            it[speakRepliesKey] = s.speakReplies
            it[continuousKey] = s.continuousConversation
            it[wakeKey] = s.wakeWordEnabled
            it[rateKey] = s.speechRate
            it[volumeKey] = s.volume
        }
    }
}
