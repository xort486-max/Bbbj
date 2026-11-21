package com.jarvis.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 비밀이 아닌 앱 설정. 비밀값(토큰)은 [TokenStore]에 저장한다. */
interface SettingsStore {
    val backendUrl: Flow<String>
    suspend fun currentBackendUrl(): String = backendUrl.first()
    suspend fun setBackendUrl(url: String)
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "jarvis_settings")

class DataStoreSettingsStore(private val context: Context) : SettingsStore {
    private val backendUrlKey = stringPreferencesKey("backend_url")

    override val backendUrl: Flow<String> = context.dataStore.data.map { it[backendUrlKey].orEmpty() }

    override suspend fun setBackendUrl(url: String) {
        context.dataStore.edit { it[backendUrlKey] = url.trim() }
    }
}
