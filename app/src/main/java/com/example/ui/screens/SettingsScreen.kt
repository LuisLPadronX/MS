package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.AudioQuality
import com.example.data.model.ThemeMode
import com.example.data.preferences.SonoraSettings
import com.example.ui.components.LiquidGlassSurface

@Composable
fun SettingsScreen(
  settings: SonoraSettings,
  onSelectTheme: (ThemeMode) -> Unit,
  onSelectAudioQuality: (AudioQuality) -> Unit,
  bottomContentPadding: PaddingValues,
) {
  LazyColumn(
    contentPadding =
      PaddingValues(
        start = 20.dp,
        end = 20.dp,
        top = 12.dp,
        bottom = bottomContentPadding.calculateBottomPadding() + 28.dp,
      ),
    verticalArrangement = Arrangement.spacedBy(16.dp),
    modifier = Modifier.fillMaxSize().testTag("settings_list"),
  ) {
    item {
      Column {
        Text(
          text = "CONFIGURACIÓN Y POLÍTICA",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = "Ajustes",
          style = MaterialTheme.typography.headlineLarge,
          color = MaterialTheme.colorScheme.onBackground,
        )
      }
    }

    // Audio Quality Section
    item {
      LiquidGlassSurface(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(18.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Rounded.HighQuality,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
              text = "Calidad de sonido",
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "Streaming directo de audio optimizado sin anuncios.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.height(12.dp))
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AudioQuality.entries.forEach { quality ->
              val selected = settings.audioQuality == quality
              LiquidGlassSurface(
                shape = RoundedCornerShape(16.dp),
                highlighted = selected,
                glassAlpha = if (selected) 0.65f else 0.36f,
                onClick = { onSelectAudioQuality(quality) },
                modifier = Modifier.fillMaxWidth().testTag("quality_${quality.name}"),
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                  Icon(
                    imageVector =
                      if (selected) Icons.Rounded.RadioButtonChecked
                      else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint =
                      if (selected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                  )
                  Spacer(modifier = Modifier.width(12.dp))
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = quality.label,
                      style = MaterialTheme.typography.titleSmall,
                      color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                      text = quality.subtitle,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }
            }
          }
        }
      }
    }

    // Material You Theme Mode Section
    item {
      LiquidGlassSurface(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(18.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Rounded.Palette,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
              text = "Apariencia monocromática",
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "Interfaz minimalista en blanco y negro con superficies Liquid Glass de bajo consumo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.height(12.dp))
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
              val selected = settings.themeMode == mode
              LiquidGlassSurface(
                shape = RoundedCornerShape(16.dp),
                highlighted = selected,
                glassAlpha = if (selected) 0.65f else 0.36f,
                onClick = { onSelectTheme(mode) },
                modifier = Modifier.fillMaxWidth().testTag("theme_${mode.name}"),
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                  Icon(
                    imageVector =
                      if (selected) Icons.Rounded.RadioButtonChecked
                      else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint =
                      if (selected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                  )
                  Spacer(modifier = Modifier.width(12.dp))
                  Text(
                    text = mode.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                }
              }
            }
          }
        }
      }
    }

    // Explicit No-Save & Anti-Distribution Legal Notice
    item {
      LiquidGlassSurface(
        shape = RoundedCornerShape(24.dp),
        highlighted = true,
        glassAlpha = 0.50f,
        modifier = Modifier.fillMaxWidth().testTag("legal_notice_card"),
      ) {
        Column(modifier = Modifier.padding(18.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Rounded.Gavel,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
              text = "Aviso legal y condiciones de uso",
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }

          Spacer(modifier = Modifier.height(10.dp))

          LiquidGlassSurface(
            shape = CircleShape,
            glassAlpha = 0.45f,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.Block,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "PROHIBIDO GUARDAR CANCIONES O DISTRIBUIR LA APP",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
              )
            }
          }

          Spacer(modifier = Modifier.height(12.dp))
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
          Spacer(modifier = Modifier.height(12.dp))

          Text(
            text =
              "1. Prohibición de descarga y guardado manual: Como respeto a los creadores y a la plataforma de origen, esta aplicación prohíbe descargar, exportar o guardar manualmente canciones, playlists o favoritos. Únicamente mantiene una caché temporal automática dentro de la app para pistas reproducidas 3 o más veces, destinada solo a una escucha continua y sin conexión.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Spacer(modifier = Modifier.height(10.dp))
          Text(
            text =
              "2. Prohibición de distribución y uso indebido: Queda terminantemente prohibido distribuir, redistribuir, copiar, comercializar, sublicenciar o utilizar esta aplicación con fines distintos al uso estrictamente personal y privado.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Spacer(modifier = Modifier.height(10.dp))
          Text(
            text =
              "3. Propósito exclusivo de escucha: Este reproductor no basa su experiencia de audio en anuncios; su único fin es ofrecer reproducción de música sin interrupciones, en una interfaz limpia y con una calidad de sonido decente.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )

          Spacer(modifier = Modifier.height(14.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Rounded.GraphicEq,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "Ms • Reproductor de streaming limpio",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}
