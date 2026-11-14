package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.MusicTrack
import com.example.ui.DEFAULT_GENRES
import com.example.ui.ExploreUiState
import com.example.ui.MusicGenre
import com.example.ui.PlayerUiState
import com.example.ui.components.LiquidGlassChip
import com.example.ui.components.LiquidGlassSurface
import com.example.ui.components.TrackRowCard

@Composable
fun ExploreScreen(
  exploreState: ExploreUiState,
  playerState: PlayerUiState,
  onSelectGenre: (MusicGenre) -> Unit,
  onRetryExplore: () -> Unit,
  onPlayTrack: (MusicTrack, List<MusicTrack>) -> Unit,
  onAddToQueue: (MusicTrack) -> Unit,
  onOpenSearch: () -> Unit,
  bottomContentPadding: PaddingValues,
) {
  LazyColumn(
    contentPadding =
      PaddingValues(
        top = 12.dp,
        bottom = bottomContentPadding.calculateBottomPadding() + 24.dp,
      ),
    verticalArrangement = Arrangement.spacedBy(14.dp),
    modifier = Modifier.fillMaxSize(),
  ) {
    // Header
    item {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
      ) {
        Column {
          Text(
            text = "STREAMING PURO • SIN ANUNCIOS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = "Explorar música",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
          )
        }
        LiquidGlassSurface(
          shape = CircleShape,
          highlighted = true,
          glassAlpha = 0.60f,
          modifier = Modifier.size(46.dp),
        ) {
          Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
              imageVector = Icons.Rounded.MusicNote,
              contentDescription = "Ms",
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
        }
      }
    }

    // Liquid Glass Hero Stage Card
    item {
      LiquidGlassSurface(
        shape = RoundedCornerShape(26.dp),
        glassAlpha = 0.50f,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(186.dp),
      ) {
        Box(modifier = Modifier.fillMaxSize()) {
          Image(
            painter = painterResource(id = R.drawable.img_hero_stage),
            contentDescription = "Escenario musical Ms",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(26.dp)),
          )
          Box(
            modifier =
              Modifier.fillMaxSize()
                .background(
                  Brush.horizontalGradient(
                    colors =
                      listOf(
                        Color.Black.copy(alpha = 0.82f),
                        Color.Black.copy(alpha = 0.45f),
                        Color.Transparent,
                      )
                  )
                )
          )
          Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxSize().padding(18.dp),
          ) {
            Column {
              LiquidGlassSurface(
                shape = CircleShape,
                highlighted = true,
                glassAlpha = 0.65f,
              ) {
                Text(
                  text = "MATERIAL YOU • LIQUID GLASS",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onPrimaryContainer,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
              }
              Spacer(modifier = Modifier.height(8.dp))
              Text(
                text = exploreState.selectedGenre.label,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              Button(
                onClick = {
                  val first = exploreState.tracks.firstOrNull()
                  if (first != null) {
                    onPlayTrack(first, exploreState.tracks)
                  } else {
                    onOpenSearch()
                  }
                },
                colors =
                  ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                  ),
                shape = CircleShape,
                modifier = Modifier.testTag("hero_play_button"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.PlayArrow,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (exploreState.tracks.isNotEmpty()) "Escuchar mix" else "Buscar música")
              }
            }
          }
        }
      }
    }

    // Genre Filter Chips
    item {
      LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(DEFAULT_GENRES, key = { it.label }) { genre ->
          val selected = genre == exploreState.selectedGenre
          LiquidGlassChip(
            label = genre.label,
            selected = selected,
            onClick = { onSelectGenre(genre) },
            modifier = Modifier.testTag("genre_chip_${genre.label}"),
          )
        }
      }
    }

    // Section title
    item {
      Text(
        text = "Selección ${exploreState.selectedGenre.label}",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(horizontal = 20.dp),
      )
    }

    when {
      exploreState.isLoading -> {
        item {
          Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
            contentAlignment = Alignment.Center,
          ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
          }
        }
      }
      exploreState.error != null -> {
        item {
          LiquidGlassSurface(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier.fillMaxWidth().padding(24.dp),
            ) {
              Text(
                text = exploreState.error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(modifier = Modifier.height(12.dp))
              OutlinedButton(onClick = onRetryExplore) {
                Icon(
                  imageVector = Icons.Rounded.Refresh,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Reintentar")
              }
            }
          }
        }
      }
      else -> {
        items(exploreState.tracks, key = { it.videoId }) { track ->
          TrackRowCard(
            track = track,
            isCurrentTrack = playerState.currentTrack?.videoId == track.videoId,
            isPlaying = playerState.isPlaying,
            onPlayClick = { onPlayTrack(track, exploreState.tracks) },
            onAddToQueue = { onAddToQueue(track) },
            modifier = Modifier.padding(horizontal = 20.dp),
          )
        }
      }
    }
  }
}
