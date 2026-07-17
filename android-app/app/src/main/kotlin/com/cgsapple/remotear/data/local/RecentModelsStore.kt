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

private val Context.recentModelsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "recent_models",
)

@Singleton
class RecentModelsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore = context.recentModelsDataStore

    val recentModelIds: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[KEY_RECENT_IDS]
            ?.split(DELIMITER)
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    suspend fun recordModelUsed(modelId: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_RECENT_IDS]
                ?.split(DELIMITER)
                ?.filter { it.isNotBlank() }
                ?.toMutableList()
                ?: mutableListOf()
            current.remove(modelId)
            current.add(0, modelId)
            while (current.size > MAX_RECENT) {
                current.removeLast()
            }
            prefs[KEY_RECENT_IDS] = current.joinToString(DELIMITER)
        }
    }

    companion object {
        private val KEY_RECENT_IDS = stringPreferencesKey("recent_model_ids")
        private const val DELIMITER = ","
        private const val MAX_RECENT = 5
    }
}
