package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.MusicTrack
import com.example.ui.PlayerUiState
import com.example.ui.SearchUiState
import com.example.ui.components.LiquidGlassChip
import com.example.ui.components.LiquidGlassSurface
import com.example.ui.components.TrackRowCard

private val QUICK_DISCOVERY_TAGS =
  listOf(
    "Daft Punk",
    "Soda Stereo",
    "Lo-Fi Beats",
    "Arctic Monkeys",
    "Bad Bunny",
    "Tame Impala",
  )

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
  searchState: SearchUiState,
  playerState: PlayerUiState,
  onQueryChange: (String) -> Unit,
  onSubmitSearch: (String?) -> Unit,
  onClearSearch: () -> Unit,
  onClearSessionQueries: () -> Unit,
  onPlayTrack: (MusicTrack, List<MusicTrack>) -> Unit,
  onAddToQueue: (MusicTrack) -> Unit,
  bottomContentPadding: PaddingValues,
) {
  val keyboardController = LocalSoftwareKeyboardController.current

  Column(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
      Text(
        text = "Buscar en streaming",
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onBackground,
      )
      Spacer(modifier = Modifier.height(12.dp))
      LiquidGlassSurface(
        shape = RoundedCornerShape(22.dp),
        glassAlpha = 0.50f,
        modifier = Modifier.fillMaxWidth(),
      ) {
        OutlinedTextField(
          value = searchState.query,
          onValueChange = onQueryChange,
          placeholder = { Text(stringResource(R.string.search_hint)) },
          singleLine = true,
          leadingIcon = {
            Icon(
              imageVector = Icons.Rounded.Search,
              contentDescription = "Buscar",
              tint = MaterialTheme.colorScheme.primary,
            )
          },
          trailingIcon = {
            if (searchState.query.isNotEmpty()) {
              IconButton(onClick = onClearSearch) {
                Icon(imageVector = Icons.Rounded.Close, contentDescription = "Limpiar búsqueda")
              }
            }
          },
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          keyboardActions =
            KeyboardActions(
              onSearch = {
                keyboardController?.hide()
                onSubmitSearch(null)
              }
            ),
          shape = RoundedCornerShape(22.dp),
          colors =
            OutlinedTextFieldDefaults.colors(
              focusedBorderColor = Color.Transparent,
              unfocusedBorderColor = Color.Transparent,
              focusedContainerColor = Color.Transparent,
              unfocusedContainerColor = Color.Transparent,
            ),
          modifier = Modifier.fillMaxWidth().testTag("search_input"),
        )
      }
    }

    when {
      searchState.isLoading -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
      }
      searchState.error != null -> {
        Box(
          modifier = Modifier.fillMaxSize().padding(24.dp),
          contentAlignment = Alignment.Center,
        ) {
          LiquidGlassSurface(shape = RoundedCornerShape(22.dp)) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier.padding(24.dp),
            ) {
              Text(
                text = searchState.error,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(modifier = Modifier.height(12.dp))
              OutlinedButton(onClick = { onSubmitSearch(null) }) { Text("Reintentar") }
            }
          }
        }
      }
      !searchState.hasSearched -> {
        LazyColumn(
          contentPadding =
            PaddingValues(
              start = 20.dp,
              end = 20.dp,
              top = 8.dp,
              bottom = bottomContentPadding.calculateBottomPadding() + 24.dp,
            ),
          verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
          if (searchState.sessionQueries.isNotEmpty()) {
            item {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text(
                  text = "Consultas de esta sesión",
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = onClearSessionQueries) { Text("Limpiar") }
              }
              Spacer(modifier = Modifier.height(8.dp))
              FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                searchState.sessionQueries.forEach { q ->
                  LiquidGlassChip(
                    label = q,
                    selected = false,
                    onClick = {
                      keyboardController?.hide()
                      onSubmitSearch(q)
                    },
                  )
                }
              }
            }
          }

          item {
            Text(
              text = "Sugerencias populares",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(10.dp))
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              QUICK_DISCOVERY_TAGS.forEach { tag ->
                LiquidGlassChip(
                  label = tag,
                  selected = false,
                  onClick = {
                    keyboardController?.hide()
                    onSubmitSearch(tag)
                  },
                )
              }
            }
          }

          item {
            LiquidGlassSurface(
              shape = RoundedCornerShape(24.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
              ) {
                Image(
                  painter = painterResource(id = R.drawable.img_empty_vinyl),
                  contentDescription = null,
                  contentScale = ContentScale.Crop,
                  modifier = Modifier.size(116.dp).clip(RoundedCornerShape(20.dp)),
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                  text = "Pega un enlace de YouTube o busca cualquier tema",
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = "Escucha al instante sin anuncios ni descargas en el dispositivo.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
      searchState.results.isEmpty() -> {
        Box(
          modifier = Modifier.fillMaxSize().padding(24.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = "Sin resultados para \"${searchState.query}\"",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      else -> {
        LazyColumn(
          contentPadding =
            PaddingValues(
              start = 20.dp,
              end = 20.dp,
              top = 4.dp,
              bottom = bottomContentPadding.calculateBottomPadding() + 24.dp,
            ),
          verticalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.fillMaxSize(),
        ) {
          items(searchState.results, key = { it.videoId }) { track ->
            TrackRowCard(
              track = track,
              isCurrentTrack = playerState.currentTrack?.videoId == track.videoId,
              isPlaying = playerState.isPlaying,
              onPlayClick = { onPlayTrack(track, searchState.results) },
              onAddToQueue = { onAddToQueue(track) },
            )
          }
        }
      }
    }
  }
}
