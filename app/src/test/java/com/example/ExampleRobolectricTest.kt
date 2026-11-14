package com.example

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.AudioCacheManager
import com.example.data.cache.CachedTrackEntry
import com.example.data.model.AudioQuality
import com.example.data.model.MusicTrack
import com.example.data.preferences.SonoraSettings
import com.example.player.BrowserIdentityInterceptor
import com.example.player.LocalStreamProxyServer
import com.example.player.ProxyResolutionResult
import com.example.player.YouTubeStreamExtractor
import com.example.ui.PlayerUiState
import com.example.ui.screens.CacheScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.SonoraTheme
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleRobolectricTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun readStringFromContext_returnsMs() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Ms", appName)
  }

  @Test
  fun settingsScreen_displaysQualityThemeAndLegalProhibitionNotice() {
    composeTestRule.setContent {
      SonoraTheme {
        SettingsScreen(
          settings = SonoraSettings(),
          onSelectTheme = {},
          onSelectAudioQuality = {},
          bottomContentPadding = PaddingValues(),
        )
      }
    }

    composeTestRule.onNodeWithText("Calidad de sonido").assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("settings_list")
      .performScrollToNode(hasText("Apariencia monocromática"))
    composeTestRule.onNodeWithText("Apariencia monocromática").assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("legal_notice_card"))
    composeTestRule.onNodeWithTag("legal_notice_card").assertExists()
    composeTestRule
      .onNodeWithText("PROHIBIDO GUARDAR CANCIONES O DISTRIBUIR LA APP", substring = true)
      .assertExists()
  }

  @Test
  fun audioCacheManager_cachesOnlyAfterThreePlaysAndClearsCleanly() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val cacheManager = AudioCacheManager(context)
    cacheManager.clearAllCacheNow()

    val payloadBytes = ByteArray(32 * 1024) { (it % 127).toByte() }
    val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    server.createContext("/audio.m4a") { exchange ->
      exchange.sendResponseHeaders(200, payloadBytes.size.toLong())
      exchange.responseBody.use { it.write(payloadBytes) }
    }
    server.start()

    try {
      val streamUrl = "http://127.0.0.1:${server.address.port}/audio.m4a"
      val track =
        MusicTrack(
          videoId = "cacheVid001",
          title = "Tema Recurrente",
          artist = "Artista Prueba",
          durationText = "3:20",
          durationSeconds = 200L,
          thumbnailUrl = "",
        )

      // 1st and 2nd plays must NOT cache to disk
      cacheManager.recordPlaybackAndProcess(track, streamUrl, emptyMap(), isHls = false)
      assertEquals(1, cacheManager.getPlayCount(track.videoId))
      assertNull(cacheManager.getOfflineFile(track.videoId))
      assertTrue(cacheManager.cachedTracks.value.isEmpty())

      cacheManager.recordPlaybackAndProcess(track, streamUrl, emptyMap(), isHls = false)
      assertEquals(2, cacheManager.getPlayCount(track.videoId))
      assertNull(cacheManager.getOfflineFile(track.videoId))
      assertTrue(cacheManager.cachedTracks.value.isEmpty())

      // 3rd play reaches AUTO_CACHE_THRESHOLD (3) and downloads for offline playback
      cacheManager.recordPlaybackAndProcess(track, streamUrl, emptyMap(), isHls = false)
      assertEquals(3, cacheManager.getPlayCount(track.videoId))
      val cachedFile = cacheManager.getOfflineFile(track.videoId)
      assertNotNull(cachedFile)
      assertEquals(payloadBytes.size.toLong(), cachedFile!!.length())
      assertEquals(1, cacheManager.cachedTracks.value.size)
      assertTrue(cacheManager.cachedTracks.value.first().isAvailableOffline)

      // Clear resets disk files and index
      cacheManager.clearAllCacheNow()
      assertNull(cacheManager.getOfflineFile(track.videoId))
      assertTrue(cacheManager.cachedTracks.value.isEmpty())
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun cacheScreen_displaysEmptyState() {
    composeTestRule.setContent {
      SonoraTheme {
        CacheScreen(
          cachedEntries = emptyList(),
          playerState = PlayerUiState(),
          onPlayTrack = { _, _ -> },
          onRemoveEntry = {},
          onClearAllCache = {},
          bottomContentPadding = PaddingValues(),
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Reproducción de Caché").assertIsDisplayed()
    composeTestRule.onNodeWithText("Tu lista de caché está vacía").assertIsDisplayed()
  }

  @Test
  fun cacheScreen_displaysPopulatedListAndPlaysCachedTrack() {
    var playedFromCache = false
    val sampleTrack =
      MusicTrack(
        videoId = "jfKfPfyJRdk",
        title = "Lofi Beats Offline",
        artist = "Lofi Girl",
        durationText = "3:45",
        durationSeconds = 225L,
        thumbnailUrl = "",
      )

    composeTestRule.setContent {
      SonoraTheme {
        CacheScreen(
          cachedEntries =
            listOf(
              CachedTrackEntry(
                track = sampleTrack,
                playCount = 4,
                fileSizeBytes = 2L * 1024L * 1024L,
                cachedAtEpochMs = 1_700_000_000_000L,
                isAvailableOffline = true,
              )
            ),
          playerState = PlayerUiState(),
          onPlayTrack = { _, _ -> playedFromCache = true },
          onRemoveEntry = {},
          onClearAllCache = {},
          bottomContentPadding = PaddingValues(),
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Reproducción de Caché").assertIsDisplayed()
    composeTestRule.onNodeWithText("Reproducir caché").assertIsDisplayed()
    composeTestRule.onNodeWithText("Lofi Beats Offline").assertIsDisplayed()
    composeTestRule.onNodeWithText("4x", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("play_all_cache_button").performClick()
    assertTrue(playedFromCache)
  }

  @Test
  fun streamExtractor_racesClientsInParallelCancelsStalledAndUsesMemoryCache() = runBlocking {
    val playerRequests = AtomicInteger(0)
    val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    server.executor = Executors.newCachedThreadPool()

    server.createContext("/youtubei/v1/visitor_id") { exchange ->
      val body = """{"responseContext":{"visitorData":"fast_visitor_123"}}""".toByteArray()
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }

    server.createContext("/youtubei/v1/player") { exchange ->
      playerRequests.incrementAndGet()
      val reqText = exchange.requestBody.bufferedReader().readText()
      when {
        reqText.contains("\"IOS\"") -> {
          // Simulate a stalled client; parallel racing cancels and returns before 2.5s
          runCatching { Thread.sleep(2500) }
          runCatching {
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
          }
        }
        reqText.contains("\"ANDROID_VR\"") -> {
          val json =
            """
            {
              "playabilityStatus": {"status": "OK"},
              "streamingData": {
                "adaptiveFormats": [
                  {"mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 64000, "url": "https://cdn.example/low.m4a"},
                  {"mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 160000, "url": "https://cdn.example/high.m4a"}
                ]
              }
            }
            """
              .trimIndent()
              .toByteArray()
          exchange.sendResponseHeaders(200, json.size.toLong())
          exchange.responseBody.use { it.write(json) }
        }
        else -> {
          exchange.sendResponseHeaders(500, -1)
          exchange.close()
        }
      }
    }

    server.start()
    try {
      val baseUrl = "http://127.0.0.1:${server.address.port}"
      val extractor =
        YouTubeStreamExtractor(
          innerTubeBaseUrl = baseUrl,
          skipNetworkProbe = true,
        )

      val startNs = System.nanoTime()
      val highStreams = extractor.extractAudioStreams("vid12345678", AudioQuality.HIGH)
      val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

      assertTrue("Expected fast winner without waiting 2.5s, got ${elapsedMs}ms", elapsedMs < 1500L)
      assertTrue(highStreams.isNotEmpty())
      assertEquals("https://cdn.example/high.m4a", highStreams.first().url)
      assertEquals("Direct (ANDROID_VR Audio)", highStreams.first().sourceLabel)

      val requestsAfterFirstCall = playerRequests.get()
      val cachedHighStreams = extractor.extractAudioStreams("vid12345678", AudioQuality.HIGH)
      assertEquals("https://cdn.example/high.m4a", cachedHighStreams.first().url)
      assertEquals(requestsAfterFirstCall, playerRequests.get())

      val saverStreams = extractor.extractAudioStreams("vid12345678", AudioQuality.DATA_SAVER)
      assertEquals("https://cdn.example/low.m4a", saverStreams.first().url)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun browserIdentityInterceptor_enforcesStandardWebBrowserUserAgentAndHeaders() {
    val observedUa = AtomicReference<String>("")
    val observedSecChUa = AtomicReference<String>("")
    val observedAcceptLang = AtomicReference<String>("")

    val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    server.createContext("/probe") { exchange ->
      observedUa.set(exchange.requestHeaders.getFirst("User-Agent").orEmpty())
      observedSecChUa.set(exchange.requestHeaders.getFirst("Sec-Ch-Ua").orEmpty())
      observedAcceptLang.set(exchange.requestHeaders.getFirst("Accept-Language").orEmpty())
      val body = "ok".toByteArray()
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    server.start()

    try {
      val client =
        OkHttpClient.Builder()
          .addInterceptor(BrowserIdentityInterceptor())
          .build()
      val req =
        Request.Builder()
          .url("http://127.0.0.1:${server.address.port}/probe")
          .header("User-Agent", "AndroidXMedia3/1.11.1 ExoPlayer")
          .get()
          .build()

      client.newCall(req).execute().use { resp -> assertTrue(resp.isSuccessful) }

      val ua = observedUa.get()
      assertEquals(YouTubeStreamExtractor.STANDARD_BROWSER_USER_AGENT, ua)
      assertTrue(ua.contains("Mozilla/5.0"))
      assertTrue(ua.contains("Chrome/"))
      assertFalse(ua.contains("ExoPlayer", ignoreCase = true))
      assertFalse(ua.contains("AndroidXMedia3", ignoreCase = true))
      assertTrue(observedSecChUa.get().contains("Google Chrome"))
      assertTrue(observedAcceptLang.get().contains("es"))
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun localStreamProxyServer_resolvesUrlFailsOverFrom403AndServesByteRanges() = runBlocking {
    val audioPayload = ByteArray(24 * 1024) { ((it * 7) % 127).toByte() }
    val expiredHits = AtomicInteger(0)

    val upstream = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    upstream.executor = Executors.newCachedThreadPool()

    upstream.createContext("/youtubei/v1/visitor_id") { exchange ->
      val body = """{"responseContext":{"visitorData":"proxy_visitor_01"}}""".toByteArray()
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }

    upstream.createContext("/youtubei/v1/player") { exchange ->
      val reqText = exchange.requestBody.bufferedReader().readText()
      if (reqText.contains("\"ANDROID_VR\"")) {
        val base = "http://127.0.0.1:${upstream.address.port}"
        val json =
          """
          {
            "playabilityStatus": {"status": "OK"},
            "streamingData": {
              "adaptiveFormats": [
                {"mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000, "url": "$base/cdn-expired"}
              ],
              "formats": [
                {"mimeType": "video/mp4", "bitrate": 128000, "url": "$base/cdn-ok"}
              ]
            }
          }
          """
            .trimIndent()
            .toByteArray()
        exchange.sendResponseHeaders(200, json.size.toLong())
        exchange.responseBody.use { it.write(json) }
      } else {
        exchange.sendResponseHeaders(500, -1)
        exchange.close()
      }
    }

    upstream.createContext("/cdn-expired") { exchange ->
      expiredHits.incrementAndGet()
      exchange.sendResponseHeaders(403, -1)
      exchange.close()
    }

    upstream.createContext("/cdn-ok") { exchange ->
      val range = exchange.requestHeaders.getFirst("Range")
      if (range != null && range.startsWith("bytes=")) {
        val parts = range.removePrefix("bytes=").split("-")
        val start = parts[0].toInt()
        val end = parts[1].toIntOrNull() ?: (audioPayload.size - 1)
        val slice = audioPayload.copyOfRange(start, end + 1)
        exchange.responseHeaders.add("Content-Type", "audio/mp4")
        exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${audioPayload.size}")
        exchange.sendResponseHeaders(206, slice.size.toLong())
        exchange.responseBody.use { it.write(slice) }
      } else {
        exchange.responseHeaders.add("Content-Type", "audio/mp4")
        exchange.sendResponseHeaders(200, audioPayload.size.toLong())
        exchange.responseBody.use { it.write(audioPayload) }
      }
    }

    upstream.start()
    val extractor =
      YouTubeStreamExtractor(
        innerTubeBaseUrl = "http://127.0.0.1:${upstream.address.port}",
        skipNetworkProbe = true,
      )
    val proxy = LocalStreamProxyServer(streamExtractor = extractor)

    try {
      val resolved = proxy.resolvePlaybackTarget("proxyVid001", AudioQuality.HIGH)
      assertTrue(resolved is ProxyResolutionResult.ProxyStream)
      val proxyUrl = (resolved as ProxyResolutionResult.ProxyStream).proxyUrl
      assertTrue(proxyUrl.startsWith("http://127.0.0.1:"))

      val exoSimClient =
        OkHttpClient.Builder()
          .addInterceptor(BrowserIdentityInterceptor())
          .build()
      val rangeReq =
        Request.Builder()
          .url(proxyUrl)
          .header("Range", "bytes=100-199")
          .get()
          .build()

      exoSimClient.newCall(rangeReq).execute().use { resp ->
        assertEquals(206, resp.code)
        assertEquals("bytes 100-199/${audioPayload.size}", resp.header("Content-Range"))
        val received = resp.body!!.bytes()
        assertArrayEquals(audioPayload.copyOfRange(100, 200), received)
      }
      assertTrue(expiredHits.get() >= 1)
    } finally {
      proxy.stop()
      upstream.stop(0)
    }
  }
}
