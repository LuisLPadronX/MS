package com.example.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.cache.AudioCacheManager
import com.example.data.cache.CachedTrackEntry
import com.example.data.model.AudioQuality
import com.example.data.model.MusicTrack
import com.example.data.model.ThemeMode
import com.example.data.preferences.SettingsRepository
import com.example.data.preferences.SonoraSettings
import com.example.data.repository.YouTubeMusicRepository
import com.example.player.YouTubePlayerEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RepeatMode {
  OFF,
  ALL,
  ONE,
}

data class MusicGenre(val label: String, val query: String)

data class PlayerUiState(
  val currentTrack: MusicTrack? = null,
  val queue: List<MusicTrack> = emptyList(),
  val currentIndex: Int = -1,
  val isPlaying: Boolean = false,
  val isBuffering: Boolean = false,
  val isEngineReady: Boolean = false,
  val isOfflineCachePlayback: Boolean = false,
  val positionSeconds: Long = 0L,
  val durationSeconds: Long = 0L,
  val shuffleEnabled: Boolean = false,
  val repeatMode: RepeatMode = RepeatMode.OFF,
  val isVideoMode: Boolean = false,
  val isWebStageActive: Boolean = false,
  val isExpanded: Boolean = false,
  val playerError: String? = null,
)

data class ExploreUiState(
  val selectedGenre: MusicGenre = DEFAULT_GENRES.first(),
  val isLoading: Boolean = false,
  val tracks: List<MusicTrack> = emptyList(),
  val error: String? = null,
)

data class SearchUiState(
  val query: String = "",
  val isLoading: Boolean = false,
  val results: List<MusicTrack> = emptyList(),
  val error: String? = null,
  val hasSearched: Boolean = false,
  val sessionQueries: List<String> = emptyList(),
)

val DEFAULT_GENRES =
  listOf(
    MusicGenre("Éxitos Globales", "top music hits official audio"),
    MusicGenre("Lo-Fi & Chill", "lofi hip hop chill beats"),
    MusicGenre("Pop Latino", "pop latino exitos musica"),
    MusicGenre("Rock Clásico", "rock clasico en español e ingles musica"),
    MusicGenre("Electrónica", "electronic dance synthwave music"),
    MusicGenre("Jazz & Acústico", "jazz acoustic instrumental music"),
  )

class SonoraViewModel(
  private val settingsRepo: SettingsRepository,
  private val youtubeRepo: YouTubeMusicRepository,
  private val audioCacheManager: AudioCacheManager,
  appContext: Context,
) : ViewModel() {

  val settingsState: StateFlow<SonoraSettings> =
    settingsRepo.settingsFlow.stateIn(
      scope = viewModelScope,
      started = SharingStarted.Eagerly,
      initialValue = SonoraSettings(),
    )

  val cachedTracksState: StateFlow<List<CachedTrackEntry>> = audioCacheManager.cachedTracks

  private val _exploreState = MutableStateFlow(ExploreUiState())
  val exploreState: StateFlow<ExploreUiState> = _exploreState.asStateFlow()

  private val _searchState = MutableStateFlow(SearchUiState())
  val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

  private val _playerState = MutableStateFlow(PlayerUiState())
  val playerState: StateFlow<PlayerUiState> = _playerState.asStateFlow()

  private val _snackbarMessage = MutableStateFlow<String?>(null)
  val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

  private var consecutiveRestrictedErrors = 0

  val playerEngine: YouTubePlayerEngine =
    YouTubePlayerEngine(
      context = appContext.applicationContext,
      audioCacheManager = audioCacheManager,
      onReadyChanged = { ready -> _playerState.update { it.copy(isEngineReady = ready) } },
      onPlaybackStateChanged = { playing, buffering ->
        if (playing) consecutiveRestrictedErrors = 0
        _playerState.update { it.copy(isPlaying = playing, isBuffering = buffering) }
      },
      onProgressChanged = { current, duration ->
        _playerState.update { state ->
          val effectiveDuration =
            if (duration > 0L) duration else state.currentTrack?.durationSeconds ?: 0L
          state.copy(positionSeconds = current, durationSeconds = effectiveDuration)
        }
      },
      onTrackEnded = { handleTrackEnded() },
      onPlaybackConfirmed = { videoId, cacheableUrl, headers, isHls, isOfflineHit ->
        _playerState.update { it.copy(isOfflineCachePlayback = isOfflineHit) }
        val track =
          _playerState.value.currentTrack?.takeIf { it.videoId == videoId }
            ?: _playerState.value.queue.firstOrNull { it.videoId == videoId }
        if (track != null) {
          audioCacheManager.recordPlaybackAndCacheIfEligible(
            track = track,
            streamUrl = cacheableUrl,
            headers = headers,
            isHls = isHls,
          )
        }
        // Prefetch the next track in queue so skipNext is instantaneous
        val state = _playerState.value
        val nextTrack = state.queue.getOrNull(state.currentIndex + 1)
        if (nextTrack != null) {
          playerEngine.prefetchNextTrack(nextTrack.videoId, settingsState.value.audioQuality)
        }
      },
      onPlaybackError = { msg, isEmbedRestricted ->
        val state = _playerState.value
        val canAutoAdvance =
          isEmbedRestricted &&
            consecutiveRestrictedErrors < 2 &&
            state.currentIndex + 1 < state.queue.size
        if (canAutoAdvance) {
          consecutiveRestrictedErrors++
          showMessage("Pista no disponible; pasando a la siguiente…")
          skipNext()
        } else {
          _playerState.update {
            it.copy(isPlaying = false, isBuffering = false, playerError = msg)
          }
        }
      },
      onWebStageActiveChanged = { active ->
        _playerState.update { it.copy(isWebStageActive = active) }
      },
    )

  init {
    loadGenre(DEFAULT_GENRES.first())
  }

  fun setThemeMode(mode: ThemeMode) {
    viewModelScope.launch { settingsRepo.setThemeMode(mode) }
  }

  fun setAudioQuality(quality: AudioQuality) {
    viewModelScope.launch {
      settingsRepo.setAudioQuality(quality)
      playerEngine.updateQuality(quality, _playerState.value.positionSeconds)
      showMessage("Calidad ajustada a: ${quality.label}")
    }
  }

  fun selectGenre(genre: MusicGenre) {
    loadGenre(genre)
  }

  fun retryExplore() {
    loadGenre(_exploreState.value.selectedGenre)
  }

  private fun loadGenre(genre: MusicGenre) {
    _exploreState.update { it.copy(selectedGenre = genre, isLoading = true, error = null) }
    viewModelScope.launch {
      youtubeRepo
        .searchTracks(genre.query)
        .onSuccess { tracks ->
          _exploreState.update {
            it.copy(isLoading = false, tracks = tracks, error = null)
          }
          // Pre-resolve the first track silently to speed up the user's first tap
          tracks.firstOrNull()?.let { first ->
            playerEngine.prefetchNextTrack(first.videoId, settingsState.value.audioQuality)
          }
        }
        .onFailure { err ->
          _exploreState.update {
            it.copy(
              isLoading = false,
              error = err.localizedMessage ?: "No se pudo conectar con YouTube.",
            )
          }
        }
    }
  }

  fun updateSearchQuery(newQuery: String) {
    _searchState.update { it.copy(query = newQuery) }
  }

  fun submitSearch(explicitQuery: String? = null) {
    val q = (explicitQuery ?: _searchState.value.query).trim()
    if (q.isEmpty()) return
    _searchState.update { state ->
      val updatedSession =
        (listOf(q) + state.sessionQueries.filterNot { it.equals(q, ignoreCase = true) }).take(6)
      state.copy(
        query = q,
        isLoading = true,
        error = null,
        hasSearched = true,
        sessionQueries = updatedSession,
      )
    }
    viewModelScope.launch {
      youtubeRepo
        .searchTracks(q)
        .onSuccess { tracks ->
          _searchState.update { it.copy(isLoading = false, results = tracks, error = null) }
        }
        .onFailure { err ->
          _searchState.update {
            it.copy(
              isLoading = false,
              error = err.localizedMessage ?: "Error al buscar en YouTube.",
            )
          }
        }
    }
  }

  fun clearSearch() {
    _searchState.update {
      it.copy(query = "", results = emptyList(), error = null, hasSearched = false)
    }
  }

  fun clearSessionQueries() {
    _searchState.update { it.copy(sessionQueries = emptyList()) }
  }

  fun removeCachedTrack(videoId: String) {
    audioCacheManager.removeCachedTrack(videoId)
    showMessage("Pista eliminada de la caché")
  }

  fun clearAllCache() {
    audioCacheManager.clearAllCache()
    showMessage("Caché vaciada")
  }

  fun playTrack(track: MusicTrack, queueTracks: List<MusicTrack> = emptyList()) {
    consecutiveRestrictedErrors = 0
    val effectiveQueue =
      when {
        queueTracks.isNotEmpty() -> {
          if (queueTracks.any { it.videoId == track.videoId }) queueTracks
          else listOf(track) + queueTracks
        }
        else -> listOf(track)
      }
    val idx = effectiveQueue.indexOfFirst { it.videoId == track.videoId }.coerceAtLeast(0)
    startPlaybackAt(effectiveQueue, idx)

    if (effectiveQueue.size == 1 && audioCacheManager.getOfflineFile(track.videoId) == null) {
      viewModelScope.launch {
        youtubeRepo.getRelatedTracks(track.videoId).onSuccess { related ->
          if (related.isNotEmpty()) {
            _playerState.update { state ->
              if (state.currentTrack?.videoId == track.videoId && state.queue.size <= 1) {
                state.copy(queue = listOf(track) + related, currentIndex = 0)
              } else {
                state
              }
            }
          }
        }
      }
    }
  }

  fun playQueueIndex(index: Int) {
    consecutiveRestrictedErrors = 0
    val q = _playerState.value.queue
    if (index in q.indices) {
      startPlaybackAt(q, index)
    }
  }

  fun addToQueue(track: MusicTrack) {
    val state = _playerState.value
    if (state.currentTrack == null) {
      playTrack(track)
      return
    }
    if (state.queue.any { it.videoId == track.videoId }) {
      showMessage("Ya está en la cola de reproducción.")
      return
    }
    _playerState.update { it.copy(queue = it.queue + track) }
    showMessage("En cola temporal: ${track.title}")
  }

  fun removeFromQueue(index: Int) {
    val state = _playerState.value
    if (index !in state.queue.indices || index == state.currentIndex) return
    val newQueue = state.queue.toMutableList().apply { removeAt(index) }
    val newIndex = if (index < state.currentIndex) state.currentIndex - 1 else state.currentIndex
    _playerState.update { it.copy(queue = newQueue, currentIndex = newIndex) }
  }

  private fun startPlaybackAt(queue: List<MusicTrack>, index: Int) {
    val track = queue.getOrNull(index) ?: return
    val isOffline = audioCacheManager.getOfflineFile(track.videoId) != null
    _playerState.update {
      it.copy(
        currentTrack = track,
        queue = queue,
        currentIndex = index,
        isBuffering = !isOffline,
        isPlaying = false,
        isOfflineCachePlayback = isOffline,
        positionSeconds = 0L,
        durationSeconds = track.durationSeconds,
        playerError = null,
      )
    }
    playerEngine.loadTrack(
      videoId = track.videoId,
      videoMode = _playerState.value.isVideoMode,
      quality = settingsState.value.audioQuality,
      startAtSeconds = 0L,
    )
  }

  fun togglePlayPause() {
    val state = _playerState.value
    if (state.currentTrack == null) return
    if (state.isPlaying) {
      playerEngine.pause()
      _playerState.update { it.copy(isPlaying = false) }
    } else {
      playerEngine.play()
      _playerState.update { it.copy(isPlaying = true, playerError = null) }
    }
  }

  fun seekTo(seconds: Long) {
    _playerState.update { it.copy(positionSeconds = seconds) }
    playerEngine.seekTo(seconds)
  }

  fun skipNext() {
    val state = _playerState.value
    if (state.queue.isEmpty()) return
    val nextIndex =
      when {
        state.shuffleEnabled && state.queue.size > 1 -> {
          val candidates = state.queue.indices.filter { it != state.currentIndex }
          candidates.random()
        }
        state.currentIndex + 1 < state.queue.size -> state.currentIndex + 1
        state.repeatMode == RepeatMode.ALL -> 0
        else -> return
      }
    startPlaybackAt(state.queue, nextIndex)
  }

  fun skipPrevious() {
    val state = _playerState.value
    if (state.queue.isEmpty()) return
    if (state.positionSeconds > 4L) {
      seekTo(0L)
      return
    }
    val prevIndex =
      when {
        state.currentIndex - 1 >= 0 -> state.currentIndex - 1
        state.repeatMode == RepeatMode.ALL -> state.queue.lastIndex
        else -> 0
      }
    startPlaybackAt(state.queue, prevIndex)
  }

  private fun handleTrackEnded() {
    val state = _playerState.value
    if (state.repeatMode == RepeatMode.ONE && state.currentTrack != null) {
      seekTo(0L)
      playerEngine.play()
      return
    }
    skipNext()
  }

  fun toggleShuffle() {
    _playerState.update { it.copy(shuffleEnabled = !it.shuffleEnabled) }
  }

  fun cycleRepeatMode() {
    _playerState.update { state ->
      val next =
        when (state.repeatMode) {
          RepeatMode.OFF -> RepeatMode.ALL
          RepeatMode.ALL -> RepeatMode.ONE
          RepeatMode.ONE -> RepeatMode.OFF
        }
      state.copy(repeatMode = next)
    }
  }

  fun setVideoMode(enabled: Boolean) {
    val curPos = _playerState.value.positionSeconds
    _playerState.update { it.copy(isVideoMode = enabled, playerError = null) }
    playerEngine.setVideoMode(
      videoMode = enabled,
      currentPositionSec = curPos,
      quality = settingsState.value.audioQuality,
    )
  }

  fun setPlayerExpanded(expanded: Boolean) {
    _playerState.update { it.copy(isExpanded = expanded) }
  }

  fun clearSnackbar() {
    _snackbarMessage.value = null
  }

  private fun showMessage(msg: String) {
    _snackbarMessage.value = msg
  }

  override fun onCleared() {
    super.onCleared()
    playerEngine.destroy()
  }
}
