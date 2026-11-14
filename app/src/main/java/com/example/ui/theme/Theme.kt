package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.example.data.model.ThemeMode

private val SonoraFallbackDarkColorScheme =
  darkColorScheme(
    primary = YouTubeSeedPrimaryDark,
    onPrimary = YouTubeOnPrimaryDark,
    primaryContainer = YouTubePrimaryContainerDark,
    onPrimaryContainer = YouTubeOnPrimaryContainerDark,
    secondary = YouTubeSecondaryDark,
    onSecondary = YouTubeOnSecondaryDark,
    secondaryContainer = YouTubeSecondaryContainerDark,
    onSecondaryContainer = YouTubeOnSecondaryContainerDark,
    tertiary = YouTubeTertiaryDark,
    onTertiary = YouTubeOnTertiaryDark,
    tertiaryContainer = YouTubeTertiaryContainerDark,
    onTertiaryContainer = YouTubeOnTertiaryContainerDark,
    background = DarkBg,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceContainer,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = DarkBg,
    surfaceContainerLow = DarkSurfaceLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceHigh,
    surfaceContainerHighest = DarkSurfaceHighest,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = SonoraErrorDark,
    onError = SonoraOnErrorDark,
  )

private val SonoraFallbackLightColorScheme =
  lightColorScheme(
    primary = YouTubeSeedPrimaryLight,
    onPrimary = YouTubeOnPrimaryLight,
    primaryContainer = YouTubePrimaryContainerLight,
    onPrimaryContainer = YouTubeOnPrimaryContainerLight,
    secondary = YouTubeSecondaryLight,
    onSecondary = YouTubeOnSecondaryLight,
    secondaryContainer = YouTubeSecondaryContainerLight,
    onSecondaryContainer = YouTubeOnSecondaryContainerLight,
    tertiary = YouTubeTertiaryLight,
    onTertiary = YouTubeOnTertiaryLight,
    tertiaryContainer = YouTubeTertiaryContainerLight,
    onTertiaryContainer = YouTubeOnTertiaryContainerLight,
    background = LightBg,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceContainer,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightSurfaceLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceHigh,
    surfaceContainerHighest = LightSurfaceHighest,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = SonoraErrorLight,
    onError = SonoraOnErrorLight,
  )

val SonoraShapes =
  Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
  )

@Composable
fun resolveDarkTheme(themeMode: ThemeMode): Boolean {
  val systemDark = isSystemInDarkTheme()
  return when (themeMode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
  }
}

@Composable
fun SonoraTheme(
  themeMode: ThemeMode = ThemeMode.SYSTEM,
  content: @Composable () -> Unit,
) {
  val darkTheme = resolveDarkTheme(themeMode)
  val colorScheme =
    if (darkTheme) SonoraFallbackDarkColorScheme else SonoraFallbackLightColorScheme

  MaterialTheme(
    colorScheme = colorScheme,
    typography = SonoraTypography,
    shapes = SonoraShapes,
    content = content,
  )
}
