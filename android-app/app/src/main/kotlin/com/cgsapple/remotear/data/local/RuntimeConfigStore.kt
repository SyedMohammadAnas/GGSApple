package com.cgsapple.remotear.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.runtimeConfigDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "runtime_config",
)

@Singleton
class RuntimeConfigStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val apiUrlOverride: Flow<String?> = context.runtimeConfigDataStore.data.map { prefs ->
        prefs[KEY_API_URL]?.takeIf { it.isNotBlank() }
    }

    val livekitUrlOverride: Flow<String?> = context.runtimeConfigDataStore.data.map { prefs ->
        prefs[KEY_LIVEKIT_URL]?.takeIf { it.isNotBlank() }
    }

    suspend fun setApiUrlOverride(url: String?) {
        context.runtimeConfigDataStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(KEY_API_URL) else prefs[KEY_API_URL] = url.trim()
        }
    }

    suspend fun setLivekitUrlOverride(url: String?) {
        context.runtimeConfigDataStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(KEY_LIVEKIT_URL) else prefs[KEY_LIVEKIT_URL] = url.trim()
        }
    }

    companion object {
        private val KEY_API_URL = stringPreferencesKey("api_url_override")
        private val KEY_LIVEKIT_URL = stringPreferencesKey("livekit_url_override")
    }
}
