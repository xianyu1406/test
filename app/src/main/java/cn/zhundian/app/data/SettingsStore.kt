package cn.zhundian.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cn.zhundian.app.ui.UiSettings
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.settingsDataStore by preferencesDataStore("zhundian_settings")
val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

class SettingsStore(private val context: Context) {
    private val key = stringPreferencesKey("settings_v1")
    val flow = context.settingsDataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { AppJson.decodeFromString<UiSettings>(it) }.getOrNull() } ?: UiSettings()
    }
    suspend fun save(settings: UiSettings) {
        context.settingsDataStore.edit { it[key] = AppJson.encodeToString(UiSettings.serializer(), settings) }
    }
}
