package com.cgsapple.remotear.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class AppMode {
    CUSTOMER,
    EXPERT,
}

private val Context.appModeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_mode",
)

@Singleton
class AppModeStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val mode: Flow<AppMode> = context.appModeDataStore.data.map { prefs ->
        if (prefs[KEY_EXPERT_MODE] == true) AppMode.EXPERT else AppMode.CUSTOMER
    }

    suspend fun setMode(mode: AppMode) {
        context.appModeDataStore.edit { prefs ->
            prefs[KEY_EXPERT_MODE] = mode == AppMode.EXPERT
        }
    }

    companion object {
        private val KEY_EXPERT_MODE = booleanPreferencesKey("expert_mode")
    }
}
