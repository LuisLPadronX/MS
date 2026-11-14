package com.example.data.model

data class MusicTrack(
  val videoId: String,
  val title: String,
  val artist: String,
  val durationText: String,
  val durationSeconds: Long,
  val thumbnailUrl: String,
) {
  val highResThumbnailUrl: String
    get() = thumbnailUrl.ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }
}

enum class AudioQuality(val label: String, val subtitle: String, val maxBitrateBps: Int) {
  HIGH(
    label = "Alta fidelidad",
    subtitle = "Máxima tasa de bits disponible (~160-256 kbps)",
    maxBitrateBps = Int.MAX_VALUE,
  ),
  BALANCED(
    label = "Equilibrada",
    subtitle = "Calidad decente y fluida (~128 kbps)",
    maxBitrateBps = 140_000,
  ),
  DATA_SAVER(
    label = "Ahorro de datos",
    subtitle = "Consumo mínimo (~64 kbps)",
    maxBitrateBps = 80_000,
  ),
}

enum class ThemeMode(val label: String) {
  SYSTEM("Automático (Material You)"),
  DARK("Oscuro"),
  LIGHT("Claro"),
}
