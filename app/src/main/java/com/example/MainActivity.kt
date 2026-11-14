package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.cache.AudioCacheManager
import com.example.data.preferences.SettingsRepository
import com.example.data.repository.YouTubeMusicRepository
import com.example.ui.SonoraAppRoot
import com.example.ui.SonoraViewModel
import com.example.ui.theme.SonoraTheme
import com.example.ui.theme.resolveDarkTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    )
    setContent {
      val viewModel: SonoraViewModel =
        viewModel(
          factory =
            viewModelFactory {
              initializer {
                val app =
                  checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                  }
                SonoraViewModel(
                  settingsRepo = SettingsRepository(app),
                  youtubeRepo = YouTubeMusicRepository(),
                  audioCacheManager = AudioCacheManager(app),
                  appContext = app,
                )
              }
            }
        )
      val settings by viewModel.settingsState.collectAsStateWithLifecycle()
      val isDark = resolveDarkTheme(settings.themeMode)

      LaunchedEffect(isDark) {
        val style =
          if (isDark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
          else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
      }

      SonoraTheme(themeMode = settings.themeMode) {
        SonoraAppRoot(viewModel = viewModel)
      }
    }
  }
}
