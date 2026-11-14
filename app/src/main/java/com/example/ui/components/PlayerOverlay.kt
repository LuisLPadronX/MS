package com.example.ui.components

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.MusicTrack
import com.example.player.YouTubePlayerEngine
import com.example.ui.PlayerUiState
import com.example.ui.RepeatMode
import kotlin.math.roundToInt

@Composable
fun MiniPlayerBar(
  state: PlayerUiState,
  onExpand: () -> Unit,
  onTogglePlayPause: () -> Unit,
  onSkipNext: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val track = state.currentTrack ?: return
  val progress =
    if (state.durationSeconds > 0L) {
      (state.positionSeconds.toFloat() / state.durationSeconds.toFloat()).coerceIn(0f, 1f)
    } else {
      0f
    }

  LiquidGlassSurface(
    shape = RoundedCornerShape(22.dp),
    highlighted = state.isPlaying,
    glassAlpha = 0.62f,
    onClick = onExpand,
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 4.dp)
        .testTag("mini_player_bar"),
  ) {
    Column {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      ) {
        AsyncImage(
          model = track.highResThumbnailUrl,
          contentDescription = track.title,
          contentScale = ContentScale.Crop,
          placeholder = painterResource(id = R.drawable.img_empty_vinyl),
          error = painterResource(id = R.drawable.img_empty_vinyl),
          modifier = Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = track.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = track.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        IconButton(
          onClick = onTogglePlayPause,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("mini_play_pause"),
        ) {
          if (state.isBuffering) {
            CircularProgressIndicator(
              modifier = Modifier.size(22.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.primary,
            )
          } else {
            Icon(
              imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
              contentDescription = if (state.isPlaying) "Pausar" else "Reproducir",
              tint = MaterialTheme.colorScheme.onSurface,
            )
          }
        }
        IconButton(
          onClick = onSkipNext,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("mini_skip_next"),
        ) {
          Icon(
            imageVector = Icons.Rounded.SkipNext,
            contentDescription = "Siguiente",
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }
      }
      LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth().height(2.5.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f),
      )
    }
  }
}

/**
 * Mounts ONE persistent AndroidView(WebView) in the root Box (kept >= 320x180dp) so YouTube
 * playback never resets or gets throttled, paired with a Liquid Glass Material You overlay.
 */
@Composable
fun PersistentYouTubeStageHost(
  playerEngine: YouTubePlayerEngine,
  state: PlayerUiState,
  onCollapse: () -> Unit,
  onTogglePlayPause: () -> Unit,
  onSeekTo: (Long) -> Unit,
  onSkipNext: () -> Unit,
  onSkipPrevious: () -> Unit,
  onToggleShuffle: () -> Unit,
  onCycleRepeat: () -> Unit,
  onVideoModeChanged: (Boolean) -> Unit,
  onSelectQueueIndex: (Int) -> Unit,
  onRemoveQueueIndex: (Int) -> Unit,
) {
  val context = LocalContext.current
  val density = LocalDensity.current
  var showQueueSheet by remember { mutableStateOf(false) }
  var stageOffset by remember { mutableStateOf(IntOffset.Zero) }
  var stageSize by remember { mutableStateOf(IntSize.Zero) }

  val showVideoOverlay = state.isExpanded && state.currentTrack != null && state.isVideoMode
  val stageWidthDp =
    if (showVideoOverlay && stageSize.width > 0) with(density) { stageSize.width.toDp() }
    else 320.dp
  val stageHeightDp =
    if (showVideoOverlay && stageSize.height > 0) with(density) { stageSize.height.toDp() }
    else 180.dp

  // Only mount WebView when Watch DOM fallback or Video mode is active, avoiding idle GPU/rendernode overhead
  if (state.isWebStageActive) {
    Box(
      modifier =
        Modifier.zIndex(if (showVideoOverlay) 4f else 1f)
          .then(
            if (showVideoOverlay && stageSize.width > 0) {
              Modifier.offset { stageOffset }
            } else {
              Modifier
            }
          )
          .size(width = stageWidthDp, height = stageHeightDp)
          .clip(RoundedCornerShape(if (showVideoOverlay) 24.dp else 0.dp))
          .graphicsLayer { alpha = if (showVideoOverlay) 1f else 0.001f }
    ) {
      AndroidView(
        factory = {
          val wv = playerEngine.getOrCreateWebView()
          (wv.parent as? ViewGroup)?.removeView(wv)
          wv.layoutParams =
            ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT,
            )
          wv
        },
        update = { wv ->
          wv.allowTouchInput = showVideoOverlay
        },
        modifier = Modifier.fillMaxSize(),
      )
    }
  }

  val expandedVisible = state.isExpanded && state.currentTrack != null
  AnimatedVisibility(
    visible = expandedVisible,
    enter =
      slideInVertically(
        initialOffsetY = { it / 4 },
        animationSpec = spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMediumLow),
      ) + fadeIn(),
    exit =
      slideOutVertically(
        targetOffsetY = { it / 4 },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
      ) + fadeOut(),
    modifier = Modifier.zIndex(2f).fillMaxSize(),
  ) {
    val track = state.currentTrack ?: return@AnimatedVisibility
    BackHandler(enabled = true) { onCollapse() }

    Box(modifier = Modifier.fillMaxSize()) {
      LiquidGlassBackdrop(artworkUrl = track.highResThumbnailUrl)

      Column(
        modifier =
          Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .widthIn(max = 620.dp)
            .align(Alignment.Center)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
      ) {
        // Header Bar
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.fillMaxWidth(),
        ) {
          LiquidGlassIconButton(
            onClick = onCollapse,
            icon = Icons.Rounded.KeyboardArrowDown,
            contentDescription = "Minimizar reproductor",
            iconSize = 28.dp,
            modifier = Modifier.testTag("collapse_player_button"),
          )

          LiquidGlassSurface(
            shape = CircleShape,
            glassAlpha = 0.46f,
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
              Text(
                text = "MS STUDIO",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
              )
              Text(
                text =
                  when {
                    state.isOfflineCachePlayback -> "Reproducción de caché • Sin conexión"
                    state.isVideoMode -> "Video en streaming"
                    else -> "Audio limpio • Sin anuncios"
                  },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiquidGlassIconButton(
              onClick = {
                val intent =
                  Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/watch?v=${track.videoId}"),
                  )
                runCatching { context.startActivity(intent) }
              },
              icon = Icons.AutoMirrored.Rounded.OpenInNew,
              contentDescription = "Abrir en YouTube",
            )
            LiquidGlassIconButton(
              onClick = { showQueueSheet = true },
              icon = Icons.AutoMirrored.Rounded.QueueMusic,
              contentDescription = "Cola de reproducción",
              modifier = Modifier.testTag("open_queue_button"),
            )
          }
        }

        // Cover / Video Toggle + Visual Stage
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiquidGlassChip(
              label = "Carátula",
              selected = !state.isVideoMode,
              onClick = { onVideoModeChanged(false) },
              leadingIcon = Icons.Rounded.MusicNote,
            )
            LiquidGlassChip(
              label = "Video",
              selected = state.isVideoMode,
              onClick = { onVideoModeChanged(true) },
              leadingIcon = Icons.Rounded.OndemandVideo,
            )
          }

          Spacer(modifier = Modifier.height(14.dp))

          val artScale by
            animateFloatAsState(
              targetValue = if (state.isPlaying) 1.0f else 0.94f,
              animationSpec =
                spring(
                  dampingRatio = Spring.DampingRatioMediumBouncy,
                  stiffness = Spring.StiffnessLow,
                ),
              label = "art_scale",
            )

          LiquidGlassSurface(
            shape = RoundedCornerShape(28.dp),
            glassAlpha = 0.36f,
            modifier =
              Modifier.fillMaxWidth()
                .then(
                  if (state.isVideoMode) {
                    Modifier.aspectRatio(16f / 9f).onGloballyPositioned { coords ->
                      val pos = coords.positionInRoot()
                      stageOffset = IntOffset(pos.x.roundToInt(), pos.y.roundToInt())
                      stageSize = coords.size
                    }
                  } else {
                    Modifier.aspectRatio(1f)
                      .widthIn(max = 300.dp)
                      .graphicsLayer {
                        scaleX = artScale
                        scaleY = artScale
                      }
                  }
                ),
          ) {
            if (!state.isVideoMode) {
              AsyncImage(
                model = track.highResThumbnailUrl,
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                placeholder = painterResource(id = R.drawable.img_empty_vinyl),
                error = painterResource(id = R.drawable.img_empty_vinyl),
                modifier = Modifier.fillMaxSize().padding(4.dp).clip(RoundedCornerShape(24.dp)),
              )
            }
          }
        }

        if (state.playerError != null) {
          LiquidGlassSurface(
            shape = RoundedCornerShape(16.dp),
            glassAlpha = 0.56f,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
              Text(
                text = state.playerError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
              )
              TextButton(onClick = onSkipNext) { Text("Siguiente") }
            }
          }
        }

        // Track Info + Glass Transport Deck
        LiquidGlassSurface(
          shape = RoundedCornerShape(28.dp),
          glassAlpha = 0.52f,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(
              text = track.title,
              style = MaterialTheme.typography.headlineSmall,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
              text = track.artist,
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(10.dp))

            var isScrubbing by remember { mutableStateOf(false) }
            var scrubValue by remember { mutableFloatStateOf(0f) }
            val maxSec = state.durationSeconds.coerceAtLeast(1L).toFloat()
            val sliderPos =
              if (isScrubbing) scrubValue else state.positionSeconds.toFloat().coerceIn(0f, maxSec)

            Slider(
              value = sliderPos,
              onValueChange = {
                isScrubbing = true
                scrubValue = it
              },
              onValueChangeFinished = {
                onSeekTo(scrubValue.toLong())
                isScrubbing = false
              },
              valueRange = 0f..maxSec,
              colors =
                SliderDefaults.colors(
                  thumbColor = MaterialTheme.colorScheme.primary,
                  activeTrackColor = MaterialTheme.colorScheme.primary,
                  inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
                ),
              modifier = Modifier.fillMaxWidth(),
            )

            Row(
              horizontalArrangement = Arrangement.SpaceBetween,
              modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            ) {
              Text(
                text = formatDurationSeconds(sliderPos.toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                text = formatDurationSeconds(state.durationSeconds),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
              horizontalArrangement = Arrangement.SpaceEvenly,
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.fillMaxWidth(),
            ) {
              IconButton(
                onClick = onToggleShuffle,
                modifier = Modifier.minimumInteractiveComponentSize(),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Shuffle,
                  contentDescription = "Aleatorio",
                  tint =
                    if (state.shuffleEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              IconButton(
                onClick = onSkipPrevious,
                modifier = Modifier.size(54.dp),
              ) {
                Icon(
                  imageVector = Icons.Rounded.SkipPrevious,
                  contentDescription = "Anterior",
                  tint = MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier.size(32.dp),
                )
              }
              Box(
                contentAlignment = Alignment.Center,
                modifier =
                  Modifier.size(68.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onTogglePlayPause)
                    .testTag("full_player_play_pause"),
              ) {
                if (state.isBuffering) {
                  CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 3.dp,
                  )
                } else {
                  Icon(
                    imageVector =
                      if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pausar" else "Reproducir",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(36.dp),
                  )
                }
              }
              IconButton(
                onClick = onSkipNext,
                modifier = Modifier.size(54.dp),
              ) {
                Icon(
                  imageVector = Icons.Rounded.SkipNext,
                  contentDescription = "Siguiente",
                  tint = MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier.size(32.dp),
                )
              }
              IconButton(
                onClick = onCycleRepeat,
                modifier = Modifier.minimumInteractiveComponentSize(),
              ) {
                Icon(
                  imageVector =
                    if (state.repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne
                    else Icons.Rounded.Repeat,
                  contentDescription = "Repetir",
                  tint =
                    if (state.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    }

    if (showQueueSheet) {
      QueueBottomSheet(
        queue = state.queue,
        currentIndex = state.currentIndex,
        onSelectIndex = { idx ->
          onSelectQueueIndex(idx)
          showQueueSheet = false
        },
        onRemoveIndex = onRemoveQueueIndex,
        onDismiss = { showQueueSheet = false },
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueBottomSheet(
  queue: List<MusicTrack>,
  currentIndex: Int,
  onSelectIndex: (Int) -> Unit,
  onRemoveIndex: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)
    ) {
      Text(
        text = "Cola temporal (${queue.size})",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = "Solo activa durante esta sesión (no se guarda en el dispositivo).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.height(14.dp))
      LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(queue, key = { idx, item -> "${idx}_${item.videoId}" }) { idx, item ->
          val active = idx == currentIndex
          LiquidGlassSurface(
            shape = RoundedCornerShape(14.dp),
            highlighted = active,
            glassAlpha = if (active) 0.65f else 0.45f,
            onClick = { onSelectIndex(idx) },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(10.dp),
            ) {
              AsyncImage(
                model = item.highResThumbnailUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
              )
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = item.title,
                  style = MaterialTheme.typography.titleSmall,
                  color =
                    if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Text(
                  text = item.artist,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              if (!active) {
                IconButton(
                  onClick = { onRemoveIndex(idx) },
                  modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                  Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Quitar de la cola",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}
