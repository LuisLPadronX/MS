package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.AudioQuality
import com.example.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sonoraSettingsDataStore: DataStore<Preferences> by
  preferencesDataStore(name = "sonora_settings")

data class SonoraSettings(
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val audioQuality: AudioQuality = AudioQuality.HIGH,
)

/**
 * Persists strictly UI and audio-stream quality preferences. No songs, playlists, favorites,
 * downloads, or playback history are ever saved.
 */
class SettingsRepository(private val context: Context) {
  private val themeKey = stringPreferencesKey("theme_mode")
  private val qualityKey = stringPreferencesKey("audio_quality")

  val settingsFlow: Flow<SonoraSettings> =
    context.sonoraSettingsDataStore.data.map { prefs ->
      val theme =
        prefs[themeKey]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
          ?: ThemeMode.SYSTEM
      val quality =
        prefs[qualityKey]?.let { name -> AudioQuality.entries.firstOrNull { it.name == name } }
          ?: AudioQuality.HIGH
      SonoraSettings(themeMode = theme, audioQuality = quality)
    }

  suspend fun setThemeMode(mode: ThemeMode) {
    context.sonoraSettingsDataStore.edit { prefs -> prefs[themeKey] = mode.name }
  }

  suspend fun setAudioQuality(quality: AudioQuality) {
    context.sonoraSettingsDataStore.edit { prefs -> prefs[qualityKey] = quality.name }
  }
}
