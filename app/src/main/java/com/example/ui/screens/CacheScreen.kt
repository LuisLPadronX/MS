package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.OfflineBolt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.cache.AudioCacheManager
import com.example.data.cache.CachedTrackEntry
import com.example.data.model.MusicTrack
import com.example.ui.PlayerUiState
import com.example.ui.components.EqualizerPulseBars
import com.example.ui.components.LiquidGlassSurface

@Composable
fun CacheScreen(
  cachedEntries: List<CachedTrackEntry>,
  playerState: PlayerUiState,
  onPlayTrack: (MusicTrack, List<MusicTrack>) -> Unit,
  onRemoveEntry: (String) -> Unit,
  onClearAllCache: () -> Unit,
  bottomContentPadding: PaddingValues,
) {
  val cacheTracks = cachedEntries.map { it.track }
  val totalMb = cachedEntries.sumOf { it.fileSizeBytes } / (1024.0 * 1024.0)

  LazyColumn(
    contentPadding =
      PaddingValues(
        start = 20.dp,
        end = 20.dp,
        top = 12.dp,
        bottom = bottomContentPadding.calculateBottomPadding() + 28.dp,
      ),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    modifier = Modifier.fillMaxSize().testTag("cache_list"),
  ) {
    item {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "SIN CONEXIÓN AUTOMÁTICO",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = "Reproducción de Caché",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
          )
        }
        if (cachedEntries.isNotEmpty()) {
          TextButton(
            onClick = onClearAllCache,
            modifier = Modifier.testTag("clear_cache_button"),
          ) {
            Text("Vaciar")
          }
        }
      }
    }

    item {
      LiquidGlassSurface(
        shape = RoundedCornerShape(22.dp),
        highlighted = true,
        glassAlpha = 0.50f,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Rounded.OfflineBolt,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text =
                if (cachedEntries.isEmpty()) "Caché inteligente activa"
                else "${cachedEntries.size} pistas listas • ${"%.1f".format(totalMb)} MB",
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.onSurface,
              fontWeight = FontWeight.SemiBold,
            )
          }
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text =
              "Las canciones reproducidas ${AudioCacheManager.AUTO_CACHE_THRESHOLD} o más veces se almacenan automáticamente aquí para reproducirse al instante y sin conexión.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (cachedEntries.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(
              onClick = {
                val first = cacheTracks.firstOrNull() ?: return@Button
                onPlayTrack(first, cacheTracks)
              },
              shape = CircleShape,
              colors =
                ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.primary,
                  contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
              modifier = Modifier.testTag("play_all_cache_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text("Reproducir caché")
            }
          }
        }
      }
    }

    if (cachedEntries.isEmpty()) {
      item {
        LiquidGlassSurface(
          shape = RoundedCornerShape(22.dp),
          modifier = Modifier.fillMaxWidth().testTag("cache_empty_card"),
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(28.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.OfflineBolt,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(40.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = "Tu lista de caché está vacía",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text =
                "Escucha cualquier canción al menos ${AudioCacheManager.AUTO_CACHE_THRESHOLD} veces y aparecerá aquí para reproducirla sin internet.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    } else {
      items(cachedEntries, key = { it.track.videoId }) { entry ->
        val track = entry.track
        val isCurrent = playerState.currentTrack?.videoId == track.videoId
        val sizeMb = entry.fileSizeBytes / (1024.0 * 1024.0)

        LiquidGlassSurface(
          shape = RoundedCornerShape(18.dp),
          highlighted = isCurrent,
          glassAlpha = if (isCurrent) 0.62f else 0.44f,
          onClick = { onPlayTrack(track, cacheTracks) },
          modifier = Modifier.fillMaxWidth().testTag("cache_row_${track.videoId}"),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
          ) {
            Box(
              modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)),
              contentAlignment = Alignment.Center,
            ) {
              AsyncImage(
                model = track.highResThumbnailUrl,
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                placeholder = painterResource(id = R.drawable.img_empty_vinyl),
                error = painterResource(id = R.drawable.img_empty_vinyl),
                modifier = Modifier.fillMaxSize(),
              )
              if (isCurrent) {
                EqualizerPulseBars(isPlaying = playerState.isPlaying)
              }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                color =
                  if (isCurrent) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Spacer(modifier = Modifier.height(2.dp))
              Text(
                text = "${track.artist}  •  ${entry.playCount}x  •  ${"%.1f".format(sizeMb)} MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            IconButton(
              onClick = { onRemoveEntry(track.videoId) },
              modifier = Modifier.minimumInteractiveComponentSize(),
            ) {
              Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = "Quitar de caché",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}
