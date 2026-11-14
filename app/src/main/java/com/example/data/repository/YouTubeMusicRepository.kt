package com.example.data.repository

import com.example.data.model.MusicTrack
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class YouTubeMusicRepository(
  private val client: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(12, TimeUnit.SECONDS)
      .readTimeout(15, TimeUnit.SECONDS)
      .build()
) {

  suspend fun searchTracks(rawQuery: String): Result<List<MusicTrack>> =
    withContext(Dispatchers.IO) {
      val query = rawQuery.trim()
      if (query.isEmpty()) {
        return@withContext Result.success(emptyList())
      }

      // 1. Direct YouTube URL or Video ID
      extractVideoId(query)?.let { videoId ->
        val resolved = fetchTrackViaOEmbed(videoId)
        if (resolved != null) {
          return@withContext Result.success(listOf(resolved))
        }
      }

      // 2. Primary: YouTube InnerTube WEB search
      val innerTubeResult = runCatching { searchInnerTube(query) }.getOrNull()
      if (!innerTubeResult.isNullOrEmpty()) {
        return@withContext Result.success(innerTubeResult)
      }

      // 3. Secondary: YouTube HTML ytInitialData extraction
      val htmlResult = runCatching { searchYouTubeHtml(query) }.getOrNull()
      if (!htmlResult.isNullOrEmpty()) {
        return@withContext Result.success(htmlResult)
      }

      // 4. Tertiary: Public Piped API search fallback
      val pipedResult = runCatching { searchPiped(query) }.getOrNull()
      if (!pipedResult.isNullOrEmpty()) {
        return@withContext Result.success(pipedResult)
      }

      Result.failure(
        IllegalStateException("No se pudieron cargar resultados de YouTube. Verifica tu conexión.")
      )
    }

  suspend fun getRelatedTracks(videoId: String): Result<List<MusicTrack>> =
    withContext(Dispatchers.IO) {
      if (!VALID_ID_REGEX.matches(videoId)) {
        return@withContext Result.success(emptyList())
      }
      val innerTubeRelated = runCatching { fetchInnerTubeNext(videoId) }.getOrNull()
      if (!innerTubeRelated.isNullOrEmpty()) {
        return@withContext Result.success(innerTubeRelated)
      }
      val pipedRelated = runCatching { fetchPipedRelated(videoId) }.getOrNull().orEmpty()
      Result.success(pipedRelated)
    }

  private fun fetchTrackViaOEmbed(videoId: String): MusicTrack? {
    val encodedUrl =
      URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", Charsets.UTF_8.name())
    val req =
      Request.Builder()
        .url("https://www.youtube.com/oembed?url=$encodedUrl&format=json")
        .header("User-Agent", USER_AGENT)
        .get()
        .build()

    client.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) return null
      val body = resp.body?.string() ?: return null
      val json = JSONObject(body)
      val title = json.optString("title", "Pista de YouTube").trim()
      val author = json.optString("author_name", "YouTube").trim()
      val thumb =
        json.optString("thumbnail_url", "https://i.ytimg.com/vi/$videoId/hqdefault.jpg").trim()
      return MusicTrack(
        videoId = videoId,
        title = title.ifBlank { "Pista de YouTube" },
        artist = author.ifBlank { "YouTube" },
        durationText = "",
        durationSeconds = 0L,
        thumbnailUrl = thumb,
      )
    }
  }

  private fun searchInnerTube(query: String): List<MusicTrack> {
    val payload =
      JSONObject().apply {
        put(
          "context",
          JSONObject().apply {
            put(
              "client",
              JSONObject().apply {
                put("clientName", "WEB")
                put("clientVersion", "2.20250218.01.00")
                put("hl", "es")
                put("gl", "US")
              },
            )
          },
        )
        put("query", query)
      }

    val request =
      Request.Builder()
        .url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
        .header("User-Agent", USER_AGENT)
        .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
        .header("X-YouTube-Client-Name", "1")
        .header("X-YouTube-Client-Version", "2.20250218.01.00")
        .header("Cookie", "CONSENT=YES+cb.20210328-17-p0.en+FX+678")
        .header("Origin", "https://www.youtube.com")
        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        .build()

    client.newCall(request).execute().use { resp ->
      if (!resp.isSuccessful) error("YouTube HTTP ${resp.code}")
      val raw = resp.body?.string() ?: error("Respuesta vacía de YouTube")
      val root = JSONObject(raw)
      val tracks = mutableListOf<MusicTrack>()
      collectVideoRenderers(root, tracks)
      return tracks.distinctBy { it.videoId }.take(25)
    }
  }

  private fun fetchInnerTubeNext(videoId: String): List<MusicTrack> {
    val payload =
      JSONObject().apply {
        put(
          "context",
          JSONObject().apply {
            put(
              "client",
              JSONObject().apply {
                put("clientName", "WEB")
                put("clientVersion", "2.20250218.01.00")
                put("hl", "es")
                put("gl", "US")
              },
            )
          },
        )
        put("videoId", videoId)
      }

    val request =
      Request.Builder()
        .url("https://www.youtube.com/youtubei/v1/next?prettyPrint=false")
        .header("User-Agent", USER_AGENT)
        .header("X-YouTube-Client-Name", "1")
        .header("X-YouTube-Client-Version", "2.20250218.01.00")
        .header("Cookie", "CONSENT=YES+cb.20210328-17-p0.en+FX+678")
        .header("Origin", "https://www.youtube.com")
        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        .build()

    client.newCall(request).execute().use { resp ->
      if (!resp.isSuccessful) error("YouTube Next HTTP ${resp.code}")
      val raw = resp.body?.string() ?: error("Respuesta vacía")
      val root = JSONObject(raw)
      val tracks = mutableListOf<MusicTrack>()
      collectVideoRenderers(root, tracks)
      return tracks.filter { it.videoId != videoId }.distinctBy { it.videoId }.take(15)
    }
  }

  private fun searchYouTubeHtml(query: String): List<MusicTrack> {
    val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
    val req =
      Request.Builder()
        .url("https://www.youtube.com/results?search_query=$encoded")
        .header("User-Agent", USER_AGENT)
        .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
        .get()
        .build()

    client.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) error("YouTube HTML HTTP ${resp.code}")
      val html = resp.body?.string() ?: return emptyList()
      val marker = "var ytInitialData = "
      val start = html.indexOf(marker)
      if (start == -1) return emptyList()
      val jsonStart = start + marker.length
      val end = html.indexOf(";</script>", jsonStart)
      if (end == -1) return emptyList()
      val jsonStr = html.substring(jsonStart, end)
      val root = JSONObject(jsonStr)
      val tracks = mutableListOf<MusicTrack>()
      collectVideoRenderers(root, tracks)
      return tracks.distinctBy { it.videoId }.take(25)
    }
  }

  private fun searchPiped(query: String): List<MusicTrack> {
    val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
    for (host in PIPED_INSTANCES) {
      val parsed =
        runCatching {
            val req =
              Request.Builder()
                .url("$host/search?q=$encoded&filter=music_songs")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
              if (!resp.isSuccessful) return@runCatching emptyList<MusicTrack>()
              val root = JSONObject(resp.body?.string() ?: return@runCatching emptyList())
              parsePipedItems(root.optJSONArray("items"))
            }
          }
          .getOrNull()
      if (!parsed.isNullOrEmpty()) return parsed.take(25)
    }
    return emptyList()
  }

  private fun fetchPipedRelated(videoId: String): List<MusicTrack> {
    for (host in PIPED_INSTANCES) {
      val parsed =
        runCatching {
            val req =
              Request.Builder()
                .url("$host/streams/$videoId")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
              if (!resp.isSuccessful) return@runCatching emptyList<MusicTrack>()
              val root = JSONObject(resp.body?.string() ?: return@runCatching emptyList())
              parsePipedItems(root.optJSONArray("relatedStreams")).filter { it.videoId != videoId }
            }
          }
          .getOrNull()
      if (!parsed.isNullOrEmpty()) return parsed.take(15)
    }
    return emptyList()
  }

  private fun parsePipedItems(items: JSONArray?): List<MusicTrack> {
    if (items == null) return emptyList()
    val tracks = mutableListOf<MusicTrack>()
    for (i in 0 until items.length()) {
      val obj = items.optJSONObject(i) ?: continue
      val rawUrl = obj.optString("url", "")
      val videoId = extractVideoId(rawUrl) ?: extractVideoId("https://www.youtube.com$rawUrl") ?: continue
      val title = obj.optString("title", "").trim()
      if (title.isBlank()) continue
      val durationSec = obj.optLong("duration", 0L)
      if (durationSec <= 0L) continue
      val artist = obj.optString("uploaderName", "YouTube Music").ifBlank { "YouTube Music" }.trim()
      val thumb =
        obj.optString("thumbnail", "").ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }
      tracks.add(
        MusicTrack(
          videoId = videoId,
          title = title.take(200),
          artist = artist.take(120),
          durationText = formatSecondsLabel(durationSec),
          durationSeconds = durationSec,
          thumbnailUrl = thumb.take(500),
        )
      )
    }
    return tracks.distinctBy { it.videoId }
  }

  private fun collectVideoRenderers(node: Any?, out: MutableList<MusicTrack>) {
    if (out.size >= 40) return
    when (node) {
      is JSONObject -> {
        node.optJSONObject("videoRenderer")?.let { vr ->
          parseVideoRenderer(vr)?.let { out.add(it) }
        }
        node.optJSONObject("compactVideoRenderer")?.let { cvr ->
          parseVideoRenderer(cvr)?.let { out.add(it) }
        }
        val keys = node.keys()
        while (keys.hasNext()) {
          val k = keys.next()
          if (k != "videoRenderer" && k != "compactVideoRenderer") {
            collectVideoRenderers(node.opt(k), out)
          }
        }
      }
      is JSONArray -> {
        for (i in 0 until node.length()) {
          collectVideoRenderers(node.opt(i), out)
        }
      }
    }
  }

  private fun parseVideoRenderer(vr: JSONObject): MusicTrack? {
    val videoId = vr.optString("videoId").trim()
    if (!VALID_ID_REGEX.matches(videoId)) return null

    val title = extractText(vr.optJSONObject("title")).trim()
    if (title.isBlank()) return null

    val artist =
      extractText(vr.optJSONObject("ownerText"))
        .ifBlank { extractText(vr.optJSONObject("longBylineText")) }
        .ifBlank { extractText(vr.optJSONObject("shortBylineText")) }
        .ifBlank { "YouTube Music" }
        .trim()

    val durationText = extractText(vr.optJSONObject("lengthText")).trim()
    val durationSeconds = parseDurationToSeconds(durationText)
    if (durationText.equals("EN VIVO", ignoreCase = true) ||
      durationText.equals("LIVE", ignoreCase = true)
    ) {
      return null
    }

    val thumbs = vr.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
    var thumbUrl = ""
    if (thumbs != null && thumbs.length() > 0) {
      thumbUrl = thumbs.getJSONObject(thumbs.length() - 1).optString("url", "")
    }
    if (thumbUrl.isBlank()) {
      thumbUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    return MusicTrack(
      videoId = videoId,
      title = title.take(200),
      artist = artist.take(120),
      durationText = durationText.ifBlank { "Audio" }.take(20),
      durationSeconds = durationSeconds,
      thumbnailUrl = thumbUrl.take(500),
    )
  }

  private fun extractText(obj: JSONObject?): String {
    if (obj == null) return ""
    val simple = obj.optString("simpleText", "")
    if (simple.isNotBlank()) return simple
    val runs = obj.optJSONArray("runs") ?: return ""
    val sb = StringBuilder()
    for (i in 0 until runs.length()) {
      sb.append(runs.optJSONObject(i)?.optString("text", "").orEmpty())
    }
    return sb.toString()
  }

  private fun formatSecondsLabel(seconds: Long): String {
    val hrs = seconds / 3600L
    val mins = (seconds % 3600L) / 60L
    val secs = seconds % 60L
    return if (hrs > 0L) "%d:%02d:%02d".format(hrs, mins, secs) else "%d:%02d".format(mins, secs)
  }

  companion object {
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val VALID_ID_REGEX = Regex("^[a-zA-Z0-9_-]{6,32}$")
    private const val USER_AGENT =
      com.example.player.YouTubeStreamExtractor.STANDARD_BROWSER_USER_AGENT

    private val PIPED_INSTANCES =
      listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://pipedapi.ducks.party",
      )

    private val YT_URL_PATTERNS =
      listOf(
        Regex("(?:youtube\\.com/watch\\?.*v=|youtu\\.be/|youtube\\.com/embed/|music\\.youtube\\.com/watch\\?.*v=)([a-zA-Z0-9_-]{11})"),
        Regex("^([a-zA-Z0-9_-]{11})$"),
      )

    fun extractVideoId(input: String): String? {
      val trimmed = input.trim()
      for (pattern in YT_URL_PATTERNS) {
        val match = pattern.find(trimmed)
        if (match != null) {
          return match.groupValues[1]
        }
      }
      return null
    }

    fun parseDurationToSeconds(durationText: String): Long {
      if (durationText.isBlank()) return 0L
      val parts = durationText.split(":").mapNotNull { it.trim().toLongOrNull() }
      return when (parts.size) {
        3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
        2 -> parts[0] * 60L + parts[1]
        1 -> parts[0]
        else -> 0L
      }.coerceIn(0L, 86400L)
    }
  }
}
