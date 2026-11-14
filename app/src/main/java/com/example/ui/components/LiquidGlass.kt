package com.example.ui.components

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun isGlassDarkMode(): Boolean =
  MaterialTheme.colorScheme.background.luminance() < 0.5f || isSystemInDarkTheme() && MaterialTheme.colorScheme.surface.luminance() < 0.5f

/**
 * Low-power ambient backdrop: static Material You radial orbs + crossfaded high-radius blurred
 * artwork when a track is active. Redraws only on track or theme changes (zero 60fps idle cost).
 */
@Composable
fun LiquidGlassBackdrop(
  artworkUrl: String?,
  modifier: Modifier = Modifier,
) {
  val scheme = MaterialTheme.colorScheme
  val isDark = scheme.background.luminance() < 0.5f
  val primaryOrb = scheme.primary.copy(alpha = if (isDark) 0.24f else 0.18f)
  val tertiaryOrb = scheme.tertiary.copy(alpha = if (isDark) 0.20f else 0.16f)
  val secondaryOrb = scheme.secondary.copy(alpha = if (isDark) 0.16f else 0.14f)

  Box(modifier = modifier.fillMaxSize().background(scheme.background)) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      drawCircle(
        brush =
          Brush.radialGradient(
            colors = listOf(primaryOrb, Color.Transparent),
            center = Offset(size.width * 0.18f, size.height * 0.14f),
            radius = size.maxDimension * 0.55f,
          ),
        radius = size.maxDimension * 0.55f,
        center = Offset(size.width * 0.18f, size.height * 0.14f),
      )
      drawCircle(
        brush =
          Brush.radialGradient(
            colors = listOf(tertiaryOrb, Color.Transparent),
            center = Offset(size.width * 0.88f, size.height * 0.36f),
            radius = size.maxDimension * 0.50f,
          ),
        radius = size.maxDimension * 0.50f,
        center = Offset(size.width * 0.88f, size.height * 0.36f),
      )
      drawCircle(
        brush =
          Brush.radialGradient(
            colors = listOf(secondaryOrb, Color.Transparent),
            center = Offset(size.width * 0.35f, size.height * 0.86f),
            radius = size.maxDimension * 0.52f,
          ),
        radius = size.maxDimension * 0.52f,
        center = Offset(size.width * 0.35f, size.height * 0.86f),
      )
    }

    if (!artworkUrl.isNullOrBlank()) {
      Crossfade(
        targetState = artworkUrl,
        animationSpec = tween(durationMillis = 550),
        label = "artwork_aura",
      ) { currentUrl ->
        val blurModifier =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Modifier.blur(radius = 24.dp, edgeTreatment = BlurredEdgeTreatment.Rectangle)
          } else {
            Modifier
          }
        AsyncImage(
          model = currentUrl,
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier =
            Modifier.fillMaxSize()
              .then(blurModifier)
              .alpha(if (isDark) 0.24f else 0.14f),
        )
      }
      Box(
        modifier =
          Modifier.fillMaxSize()
            .background(
              Brush.verticalGradient(
                colors =
                  listOf(
                    scheme.background.copy(alpha = 0.28f),
                    scheme.background.copy(alpha = 0.68f),
                    scheme.background.copy(alpha = 0.90f),
                  )
              )
            )
      )
    }
  }
}

/**
 * Cohesive Liquid Glass container with translucent frosted fill, inner diagonal specular gloss,
 * crisp luminous border, and optional low-power spring press physics.
 */
@Composable
fun LiquidGlassSurface(
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(22.dp),
  highlighted: Boolean = false,
  glassAlpha: Float? = null,
  onClick: (() -> Unit)? = null,
  content: @Composable BoxScope.() -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  val isDark = scheme.background.luminance() < 0.5f
  val interactionSource = remember { MutableInteractionSource() }
  val pressed by interactionSource.collectIsPressedAsState()

  val pressScale by
    animateFloatAsState(
      targetValue = if (pressed && onClick != null) 0.978f else 1f,
      animationSpec =
        spring(
          dampingRatio = Spring.DampingRatioMediumBouncy,
          stiffness = Spring.StiffnessMediumLow,
        ),
      label = "glass_press_scale",
    )

  val targetFill =
    when {
      highlighted -> scheme.primaryContainer.copy(alpha = glassAlpha ?: if (isDark) 0.50f else 0.68f)
      else -> scheme.surfaceContainerHigh.copy(alpha = glassAlpha ?: if (isDark) 0.44f else 0.64f)
    }
  val animatedFill by
    animateColorAsState(
      targetValue = targetFill,
      animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
      label = "glass_fill",
    )

  val topHighlight =
    if (isDark) Color.White.copy(alpha = if (highlighted) 0.36f else 0.24f)
    else Color.White.copy(alpha = 0.84f)
  val bottomBorder =
    if (highlighted) scheme.primary.copy(alpha = 0.42f)
    else scheme.outlineVariant.copy(alpha = if (isDark) 0.28f else 0.45f)

  val sheenStart =
    if (isDark) Color.White.copy(alpha = 0.11f) else Color.White.copy(alpha = 0.42f)

  val clickModifier =
    if (onClick != null) {
      Modifier.clickable(
        interactionSource = interactionSource,
        indication = ripple(color = scheme.primary),
        onClick = onClick,
      )
    } else {
      Modifier
    }

  Box(
    modifier =
      modifier
        .graphicsLayer {
          scaleX = pressScale
          scaleY = pressScale
        }
        .clip(shape)
        .background(animatedFill)
        .background(
          Brush.linearGradient(
            colors = listOf(sheenStart, Color.Transparent, scheme.primary.copy(alpha = 0.04f))
          )
        )
        .border(
          width = 1.dp,
          brush =
            Brush.verticalGradient(
              colors =
                listOf(
                  topHighlight,
                  Color.White.copy(alpha = if (isDark) 0.08f else 0.30f),
                  bottomBorder,
                )
            ),
          shape = shape,
        )
        .then(clickModifier),
    content = content,
  )
}

@Composable
fun LiquidGlassChip(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  leadingIcon: ImageVector? = null,
) {
  val scheme = MaterialTheme.colorScheme
  val scale by
    animateFloatAsState(
      targetValue = if (selected) 1.02f else 1f,
      animationSpec =
        spring(
          dampingRatio = Spring.DampingRatioMediumBouncy,
          stiffness = Spring.StiffnessMediumLow,
        ),
      label = "chip_scale",
    )
  val contentColor by
    animateColorAsState(
      targetValue = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
      animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
      label = "chip_content",
    )

  LiquidGlassSurface(
    shape = CircleShape,
    highlighted = selected,
    glassAlpha = if (selected) 0.78f else 0.44f,
    onClick = onClick,
    modifier =
      modifier
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
        }
        .minimumInteractiveComponentSize(),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
      if (leadingIcon != null) {
        Icon(
          imageVector = leadingIcon,
          contentDescription = null,
          tint = contentColor,
          modifier = Modifier.size(16.dp),
        )
      }
      Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        color = contentColor,
        maxLines = 1,
      )
    }
  }
}

@Composable
fun LiquidGlassIconButton(
  onClick: () -> Unit,
  icon: ImageVector,
  contentDescription: String,
  modifier: Modifier = Modifier,
  tint: Color = MaterialTheme.colorScheme.onSurface,
  highlighted: Boolean = false,
  size: Dp = 48.dp,
  iconSize: Dp = 22.dp,
) {
  LiquidGlassSurface(
    shape = CircleShape,
    highlighted = highlighted,
    glassAlpha = if (highlighted) 0.76f else 0.46f,
    onClick = onClick,
    modifier = modifier.size(size).minimumInteractiveComponentSize(),
  ) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
      Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier.size(iconSize),
      )
    }
  }
}

data class GlassNavDestination<T>(
  val key: T,
  val label: String,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
  val testTag: String,
)

@Composable
fun <T> LiquidGlassNavBar(
  destinations: List<GlassNavDestination<T>>,
  selectedKey: T,
  onSelect: (T) -> Unit,
  modifier: Modifier = Modifier,
) {
  val scheme = MaterialTheme.colorScheme
  val isDark = scheme.background.luminance() < 0.5f

  Box(
    modifier =
      modifier
        .fillMaxWidth()
        .windowInsetsPadding(WindowInsets.navigationBars)
        .padding(horizontal = 16.dp, vertical = 8.dp)
  ) {
    LiquidGlassSurface(
      shape = RoundedCornerShape(28.dp),
      glassAlpha = if (isDark) 0.58f else 0.78f,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
      ) {
        destinations.forEach { dest ->
          val selected = dest.key == selectedKey
          val pillColor by
            animateColorAsState(
              targetValue =
                if (selected) scheme.primaryContainer.copy(alpha = 0.85f) else Color.Transparent,
              animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
              label = "nav_pill_bg",
            )
          val itemColor by
            animateColorAsState(
              targetValue = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
              animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
              label = "nav_item_fg",
            )

          Box(
            contentAlignment = Alignment.Center,
            modifier =
              Modifier.weight(1f)
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(22.dp))
                .background(pillColor)
                .clickable { onSelect(dest.key) }
                .padding(vertical = 8.dp, horizontal = 6.dp)
                .testTag(dest.testTag),
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Icon(
                imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                contentDescription = dest.label,
                tint = itemColor,
                modifier = Modifier.size(22.dp),
              )
              Spacer(modifier = Modifier.height(2.dp))
              Text(
                text = dest.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = itemColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
fun <T> LiquidGlassNavRail(
  destinations: List<GlassNavDestination<T>>,
  selectedKey: T,
  onSelect: (T) -> Unit,
  modifier: Modifier = Modifier,
) {
  val scheme = MaterialTheme.colorScheme
  val isDark = scheme.background.luminance() < 0.5f

  Box(modifier = modifier.fillMaxHeight().padding(start = 12.dp, top = 12.dp, bottom = 12.dp)) {
    LiquidGlassSurface(
      shape = RoundedCornerShape(28.dp),
      glassAlpha = if (isDark) 0.56f else 0.76f,
      modifier = Modifier.fillMaxHeight().width(86.dp),
    ) {
      Column(
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().padding(vertical = 16.dp, horizontal = 8.dp),
      ) {
        destinations.forEach { dest ->
          val selected = dest.key == selectedKey
          val pillColor by
            animateColorAsState(
              targetValue =
                if (selected) scheme.primaryContainer.copy(alpha = 0.85f) else Color.Transparent,
              animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
              label = "rail_pill_bg",
            )
          val itemColor by
            animateColorAsState(
              targetValue = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
              animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
              label = "rail_item_fg",
            )

          Box(
            contentAlignment = Alignment.Center,
            modifier =
              Modifier.fillMaxWidth()
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(20.dp))
                .background(pillColor)
                .clickable { onSelect(dest.key) }
                .padding(vertical = 10.dp)
                .testTag(dest.testTag),
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Icon(
                imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                contentDescription = dest.label,
                tint = itemColor,
                modifier = Modifier.size(22.dp),
              )
              Spacer(modifier = Modifier.height(3.dp))
              Text(
                text = dest.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = itemColor,
                maxLines = 1,
              )
            }
          }
        }
      }
    }
  }
}
