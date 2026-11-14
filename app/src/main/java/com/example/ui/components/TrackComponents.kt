package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.MusicTrack

fun formatDurationSeconds(seconds: Long): String {
  if (seconds <= 0L) return "0:00"
  val hrs = seconds / 3600L
  val mins = (seconds % 3600L) / 60L
  val secs = seconds % 60L
  return if (hrs > 0L) {
    "%d:%02d:%02d".format(hrs, mins, secs)
  } else {
    "%d:%02d".format(mins, secs)
  }
}

@Composable
fun TrackRowCard(
  track: MusicTrack,
  isCurrentTrack: Boolean,
  isPlaying: Boolean,
  onPlayClick: () -> Unit,
  onAddToQueue: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var menuExpanded by remember { mutableStateOf(false) }

  LiquidGlassSurface(
    shape = RoundedCornerShape(18.dp),
    highlighted = isCurrentTrack,
    glassAlpha = if (isCurrentTrack) 0.62f else 0.44f,
    onClick = onPlayClick,
    modifier = modifier.fillMaxWidth().testTag("track_row_${track.videoId}"),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
      Box(
        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)),
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
        if (isCurrentTrack) {
          Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.50f)),
            contentAlignment = Alignment.Center,
          ) {
            EqualizerPulseBars(isPlaying = isPlaying)
          }
        }
      }

      Spacer(modifier = Modifier.width(12.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = track.title,
          style = MaterialTheme.typography.titleMedium,
          color =
            if (isCurrentTrack) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = track.artist,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
          )
          if (track.durationText.isNotBlank()) {
            Text(
              text = "  •  ${track.durationText}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
            )
          }
        }
      }

      IconButton(
        onClick = onAddToQueue,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("queue_add_${track.videoId}"),
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
          contentDescription = "Agregar a la cola",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Box {
        IconButton(
          onClick = { menuExpanded = true },
          modifier = Modifier.minimumInteractiveComponentSize(),
        ) {
          Icon(
            imageVector = Icons.Rounded.MoreVert,
            contentDescription = "Más opciones",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        DropdownMenu(
          expanded = menuExpanded,
          onDismissRequest = { menuExpanded = false },
        ) {
          DropdownMenuItem(
            text = { Text("Reproducir ahora") },
            leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
            onClick = {
              menuExpanded = false
              onPlayClick()
            },
          )
          DropdownMenuItem(
            text = { Text("Agregar a la cola") },
            leadingIcon = {
              Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null)
            },
            onClick = {
              menuExpanded = false
              onAddToQueue()
            },
          )
          DropdownMenuItem(
            text = { Text("Abrir en YouTube") },
            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
            onClick = {
              menuExpanded = false
              val intent =
                Intent(
                  Intent.ACTION_VIEW,
                  Uri.parse("https://www.youtube.com/watch?v=${track.videoId}"),
                )
              runCatching { context.startActivity(intent) }
            },
          )
        }
      }
    }
  }
}

@Composable
fun EqualizerPulseBars(isPlaying: Boolean, modifier: Modifier = Modifier) {
  if (!isPlaying) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(3.dp),
      verticalAlignment = Alignment.Bottom,
      modifier = modifier.height(18.dp),
    ) {
      repeat(3) {
        Box(
          modifier =
            Modifier.width(4.dp)
              .height(7.dp)
              .clip(RoundedCornerShape(2.dp))
              .background(MaterialTheme.colorScheme.primary)
        )
      }
    }
    return
  }

  val transition = rememberInfiniteTransition(label = "eq")
  val b1 by
    transition.animateFloat(
      initialValue = 0.3f,
      targetValue = 1.0f,
      animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse),
      label = "b1",
    )
  val b2 by
    transition.animateFloat(
      initialValue = 0.9f,
      targetValue = 0.25f,
      animationSpec = infiniteRepeatable(tween(340), RepeatMode.Reverse),
      label = "b2",
    )
  val b3 by
    transition.animateFloat(
      initialValue = 0.45f,
      targetValue = 0.95f,
      animationSpec = infiniteRepeatable(tween(490), RepeatMode.Reverse),
      label = "b3",
    )

  Row(
    horizontalArrangement = Arrangement.spacedBy(3.dp),
    verticalAlignment = Alignment.Bottom,
    modifier = modifier.height(18.dp),
  ) {
    listOf(b1, b2, b3).forEach { frac ->
      Box(
        modifier =
          Modifier.width(4.dp)
            .height((18f * frac).dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.primary)
      )
    }
  }
}
