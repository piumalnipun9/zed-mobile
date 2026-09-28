package dev.zed.mobile

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.zedConnectionSettingsDataStore by preferencesDataStore(name = "zed_mobile_settings")
private val stayConnectedKey = booleanPreferencesKey("stay_connected_for_agent_notifications")

/**
 * Persists the user's choice to keep the direct desktop event connection active. The default is
 * intentionally enabled for newly paired phones.
 */
class AndroidConnectionSettingsStore(context: Context) {
    private val appContext = context.applicationContext

    val stayConnected: Flow<Boolean> = appContext.zedConnectionSettingsDataStore.data.map { preferences ->
        stayConnectedOrDefault(preferences[stayConnectedKey])
    }

    suspend fun setStayConnected(enabled: Boolean) {
        appContext.zedConnectionSettingsDataStore.edit { preferences ->
            preferences[stayConnectedKey] = enabled
        }
    }
}

internal fun stayConnectedOrDefault(value: Boolean?): Boolean = value ?: true
