package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.OfflineBolt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.OfflineBolt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.GlassNavDestination
import com.example.ui.components.LiquidGlassBackdrop
import com.example.ui.components.LiquidGlassNavBar
import com.example.ui.components.LiquidGlassNavRail
import com.example.ui.components.MiniPlayerBar
import com.example.ui.components.PersistentYouTubeStageHost
import com.example.ui.screens.CacheScreen
import com.example.ui.screens.ExploreScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen

enum class SonoraTab(
  val label: String,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
) {
  EXPLORE("Explorar", Icons.Rounded.Explore, Icons.Outlined.Explore),
  SEARCH("Buscar", Icons.Rounded.Search, Icons.Outlined.Search),
  CACHE("Caché", Icons.Rounded.OfflineBolt, Icons.Outlined.OfflineBolt),
  SETTINGS("Ajustes", Icons.Rounded.Settings, Icons.Outlined.Settings),
}

@Composable
fun SonoraAppRoot(viewModel: SonoraViewModel) {
  val settings by viewModel.settingsState.collectAsStateWithLifecycle()
  val cachedEntries by viewModel.cachedTracksState.collectAsStateWithLifecycle()
  val exploreState by viewModel.exploreState.collectAsStateWithLifecycle()
  val searchState by viewModel.searchState.collectAsStateWithLifecycle()
  val playerState by viewModel.playerState.collectAsStateWithLifecycle()
  val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()

  var selectedTab by remember { mutableStateOf(SonoraTab.EXPLORE) }
  val snackbarHostState = remember { SnackbarHostState() }

  LaunchedEffect(snackbarMessage) {
    val msg = snackbarMessage ?: return@LaunchedEffect
    snackbarHostState.showSnackbar(msg)
    viewModel.clearSnackbar()
  }

  BackHandler(enabled = selectedTab != SonoraTab.EXPLORE && !playerState.isExpanded) {
    selectedTab = SonoraTab.EXPLORE
  }

  val navDestinations = remember {
    SonoraTab.entries.map { tab ->
      GlassNavDestination(
        key = tab,
        label = tab.label,
        selectedIcon = tab.selectedIcon,
        unselectedIcon = tab.unselectedIcon,
        testTag = "nav_tab_${tab.name.lowercase()}",
      )
    }
  }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val useNavRail = maxWidth >= 600.dp

    Box(modifier = Modifier.fillMaxSize()) {
      LiquidGlassBackdrop(artworkUrl = playerState.currentTrack?.highResThumbnailUrl)

      Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
          if (!useNavRail) {
            Column {
              if (playerState.currentTrack != null) {
                MiniPlayerBar(
                  state = playerState,
                  onExpand = { viewModel.setPlayerExpanded(true) },
                  onTogglePlayPause = viewModel::togglePlayPause,
                  onSkipNext = viewModel::skipNext,
                )
              }
              LiquidGlassNavBar(
                destinations = navDestinations,
                selectedKey = selectedTab,
                onSelect = { selectedTab = it },
              )
            }
          } else if (playerState.currentTrack != null) {
            Box(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
              MiniPlayerBar(
                state = playerState,
                onExpand = { viewModel.setPlayerExpanded(true) },
                onTogglePlayPause = viewModel::togglePlayPause,
                onSkipNext = viewModel::skipNext,
              )
            }
          }
        },
      ) { innerPadding ->
        Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
          if (useNavRail) {
            LiquidGlassNavRail(
              destinations = navDestinations,
              selectedKey = selectedTab,
              onSelect = { selectedTab = it },
            )
          }

          Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val contentPadding = PaddingValues(bottom = 12.dp)
            Crossfade(
              targetState = selectedTab,
              animationSpec = tween(durationMillis = 220),
              label = "tab_crossfade",
            ) { tab ->
              when (tab) {
                SonoraTab.EXPLORE ->
                  ExploreScreen(
                    exploreState = exploreState,
                    playerState = playerState,
                    onSelectGenre = viewModel::selectGenre,
                    onRetryExplore = viewModel::retryExplore,
                    onPlayTrack = viewModel::playTrack,
                    onAddToQueue = viewModel::addToQueue,
                    onOpenSearch = { selectedTab = SonoraTab.SEARCH },
                    bottomContentPadding = contentPadding,
                  )
                SonoraTab.SEARCH ->
                  SearchScreen(
                    searchState = searchState,
                    playerState = playerState,
                    onQueryChange = viewModel::updateSearchQuery,
                    onSubmitSearch = viewModel::submitSearch,
                    onClearSearch = viewModel::clearSearch,
                    onClearSessionQueries = viewModel::clearSessionQueries,
                    onPlayTrack = viewModel::playTrack,
                    onAddToQueue = viewModel::addToQueue,
                    bottomContentPadding = contentPadding,
                  )
                SonoraTab.CACHE ->
                  CacheScreen(
                    cachedEntries = cachedEntries,
                    playerState = playerState,
                    onPlayTrack = viewModel::playTrack,
                    onRemoveEntry = viewModel::removeCachedTrack,
                    onClearAllCache = viewModel::clearAllCache,
                    bottomContentPadding = contentPadding,
                  )
                SonoraTab.SETTINGS ->
                  SettingsScreen(
                    settings = settings,
                    onSelectTheme = viewModel::setThemeMode,
                    onSelectAudioQuality = viewModel::setAudioQuality,
                    bottomContentPadding = contentPadding,
                  )
              }
            }
          }
        }
      }

      PersistentYouTubeStageHost(
        playerEngine = viewModel.playerEngine,
        state = playerState,
        onCollapse = { viewModel.setPlayerExpanded(false) },
        onTogglePlayPause = viewModel::togglePlayPause,
        onSeekTo = viewModel::seekTo,
        onSkipNext = viewModel::skipNext,
        onSkipPrevious = viewModel::skipPrevious,
        onToggleShuffle = viewModel::toggleShuffle,
        onCycleRepeat = viewModel::cycleRepeatMode,
        onVideoModeChanged = viewModel::setVideoMode,
        onSelectQueueIndex = viewModel::playQueueIndex,
        onRemoveQueueIndex = viewModel::removeFromQueue,
      )
    }
  }
}
