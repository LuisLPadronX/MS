package com.example.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.data.cache.AudioCacheManager
import com.example.data.model.AudioQuality
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Low-latency hybrid player engine:
 * 1) Plays directly from AudioCacheManager offline files (<30ms) when a track was auto-cached
 *    after 3+ plays.
 * 2) Otherwise races parallel stream extractors and plays via Media3 ExoPlayer + OkHttpDataSource
 *    with fast-start DefaultLoadControl (600ms initial buffer).
 * 3) Falls back to un-occluded m.youtube.com/watch DOM <video> bridge when video mode is enabled
 *    or direct extraction is unavailable.
 */
@OptIn(UnstableApi::class)
class YouTubePlayerEngine(
  context: Context,
  private val audioCacheManager: AudioCacheManager,
  private val onReadyChanged: (Boolean) -> Unit,
  private val onPlaybackStateChanged: (isPlaying: Boolean, isBuffering: Boolean) -> Unit,
  private val onProgressChanged: (currentSeconds: Long, durationSeconds: Long) -> Unit,
  private val onTrackEnded: () -> Unit,
  private val onPlaybackConfirmed: (
    videoId: String,
    cacheableUrl: String?,
    headers: Map<String, String>,
    isHls: Boolean,
    isOfflineCacheHit: Boolean,
  ) -> Unit,
  private val onPlaybackError: (message: String, isEmbedRestricted: Boolean) -> Unit,
  private val onWebStageActiveChanged: (Boolean) -> Unit = {},
) {
  private val appContext = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val streamExtractor = YouTubeStreamExtractor()
  private val streamProxyServer = LocalStreamProxyServer(streamExtractor, audioCacheManager)

  private val streamHttpClient: OkHttpClient =
    OkHttpClient.Builder()
      .addInterceptor(BrowserIdentityInterceptor(YouTubeStreamExtractor.STANDARD_BROWSER_USER_AGENT))
      .connectTimeout(6, TimeUnit.SECONDS)
      .readTimeout(15, TimeUnit.SECONDS)
      .followRedirects(true)
      .followSslRedirects(true)
      .build()

  private val okHttpDataSourceFactory =
    OkHttpDataSource.Factory(streamHttpClient)
      .setUserAgent(YouTubeStreamExtractor.STANDARD_BROWSER_USER_AGENT)
      .setDefaultRequestProperties(BROWSER_DEFAULT_HEADERS)
  private val defaultDataSourceFactory = DefaultDataSource.Factory(appContext, okHttpDataSourceFactory)

  private val fastLoadControl =
    DefaultLoadControl.Builder()
      .setBufferDurationsMs(
        15_000, // minBufferMs
        30_000, // maxBufferMs
        600,    // bufferForPlaybackMs (fast start)
        1_200,  // bufferForPlaybackAfterRebufferMs
      )
      .build()

  private val exoPlayer: ExoPlayer =
    ExoPlayer.Builder(appContext)
      .setLoadControl(fastLoadControl)
      .setMediaSourceFactory(DefaultMediaSourceFactory(defaultDataSourceFactory))
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(C.USAGE_MEDIA)
          .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
          .build(),
        true,
      )
      .build()

  private var activeVideoId: String? = null
  private var useWebVideoMode = false
  private var currentQuality = AudioQuality.HIGH
  private var loadJob: Job? = null
  private var prefetchJob: Job? = null

  private var activeModeIsNative = false
  private var pendingSeekSec: Long = 0L
  private var pendingWatchCandidates: List<ExtractedAudioStream> = emptyList()
  private var activeStreamForConfirm: ExtractedAudioStream? = null
  private var activeIsOfflineHit = false
  private var confirmedForCurrentLoad = false

  private val nativeProgressTicker =
    object : Runnable {
      override fun run() {
        if (activeModeIsNative) {
          val curSec = (exoPlayer.currentPosition / 1000L).coerceAtLeast(0L)
          val rawDur = exoPlayer.duration
          val durSec = if (rawDur != C.TIME_UNSET && rawDur > 0L) rawDur / 1000L else 0L
          onProgressChanged(curSec, durSec)
          if (exoPlayer.isPlaying) {
            mainHandler.postDelayed(this, 500L)
          }
        }
      }
    }

  init {
    onReadyChanged(true)
    scope.launch(Dispatchers.IO) {
      streamProxyServer.start()
      streamExtractor.warmUp()
    }

    exoPlayer.addListener(
      object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
          if (!activeModeIsNative) return
          when (playbackState) {
            Player.STATE_BUFFERING -> onPlaybackStateChanged(false, true)
            Player.STATE_READY -> {
              val rawDur = exoPlayer.duration
              val durSec = if (rawDur != C.TIME_UNSET && rawDur > 0L) rawDur / 1000L else 0L
              val curSec = (exoPlayer.currentPosition / 1000L).coerceAtLeast(0L)
              onProgressChanged(curSec, durSec)
              onPlaybackStateChanged(exoPlayer.isPlaying, false)
              confirmPlaybackOnce()
              if (exoPlayer.isPlaying) startTicker()
            }
            Player.STATE_ENDED -> {
              stopTicker()
              onPlaybackStateChanged(false, false)
              onTrackEnded()
            }
            Player.STATE_IDLE -> Unit
          }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
          if (!activeModeIsNative) return
          val isBuffering = exoPlayer.playbackState == Player.STATE_BUFFERING
          onPlaybackStateChanged(isPlaying, isBuffering)
          if (isPlaying) {
            confirmPlaybackOnce()
            startTicker()
          } else {
            stopTicker()
          }
        }

        override fun onPlayerError(error: PlaybackException) {
          if (!activeModeIsNative) return
          val vid = activeVideoId ?: return
          Log.w(TAG, "ExoPlayer error (${error.errorCodeName}) for $vid", error)
          stopNativePlayer()
          if (!useWebVideoMode) {
            val nextCandidate = pendingWatchCandidates.firstOrNull()
            if (nextCandidate != null) {
              pendingWatchCandidates = pendingWatchCandidates.drop(1)
              startNativeUriPlayback(vid, nextCandidate.url, nextCandidate, nextCandidate.isHls, pendingSeekSec)
            } else {
              startWatchWebPlayback(vid, pendingSeekSec)
            }
          }
        }
      }
    )
  }

  class PassThroughWebView(context: Context) : WebView(context) {
    var allowTouchInput: Boolean = false

    override fun onTouchEvent(event: MotionEvent?): Boolean =
      if (allowTouchInput) super.onTouchEvent(event) else false

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean =
      if (allowTouchInput) super.onInterceptTouchEvent(ev) else false
  }

  private var webViewInstance: PassThroughWebView? = null

  @SuppressLint("SetJavaScriptEnabled")
  fun getOrCreateWebView(): PassThroughWebView {
    webViewInstance?.let { return it }
    val created =
      PassThroughWebView(appContext).apply {
        val wv = this
        setBackgroundColor(Color.BLACK)
        CookieManager.getInstance().apply {
          setAcceptCookie(true)
          setAcceptThirdPartyCookies(wv, true)
          setCookie("https://m.youtube.com", "CONSENT=YES+cb.20210328-17-p0.en+FX+678")
          setCookie("https://www.youtube.com", "CONSENT=YES+cb.20210328-17-p0.en+FX+678")
        }
        settings.apply {
          javaScriptEnabled = true
          domStorageEnabled = true
          mediaPlaybackRequiresUserGesture = false
          useWideViewPort = true
          loadWithOverviewMode = true
          userAgentString = MOBILE_CHROME_UA
          mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        webChromeClient =
          object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
              if (consoleMessage != null &&
                consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR
              ) {
                Log.w(TAG, "WebView JS Error: ${consoleMessage.message()}")
              }
              return super.onConsoleMessage(consoleMessage)
            }
          }
        webViewClient =
          object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
              view: WebView?,
              request: WebResourceRequest?,
            ): Boolean {
              val host = request?.url?.host.orEmpty()
              return !(host.endsWith("youtube.com") ||
                host.endsWith("youtu.be") ||
                host.endsWith("googlevideo.com") ||
                host.endsWith("ytimg.com") ||
                host.endsWith("google.com"))
            }

            override fun onPageFinished(view: WebView?, url: String?) {
              super.onPageFinished(view, url)
              if (!activeModeIsNative && url?.contains("watch") == true) {
                injectWatchDomBridge(view)
              }
            }

            override fun onReceivedError(
              view: WebView?,
              request: WebResourceRequest?,
              error: WebResourceError?,
            ) {
              if (request?.isForMainFrame == true) {
                Log.w(TAG, "WebView load error: ${error?.description}")
                onPlaybackStateChanged(false, false)
                onPlaybackError("Sin conexión a internet para cargar esta pista.", false)
              }
            }
          }
        addJavascriptInterface(PlayerBridge(), "SonoraBridge")
      }
    webViewInstance = created
    return created
  }

  fun prefetchNextTrack(videoId: String?, quality: AudioQuality = currentQuality) {
    if (videoId.isNullOrBlank()) return
    if (audioCacheManager.getOfflineFile(videoId) != null) return
    prefetchJob?.cancel()
    prefetchJob = scope.launch { streamExtractor.prefetch(videoId, quality) }
  }

  fun loadTrack(
    videoId: String,
    videoMode: Boolean,
    quality: AudioQuality = currentQuality,
    startAtSeconds: Long = 0L,
  ) {
    activeVideoId = videoId
    useWebVideoMode = videoMode
    currentQuality = quality
    pendingSeekSec = startAtSeconds
    confirmedForCurrentLoad = false
    activeStreamForConfirm = null
    activeIsOfflineHit = false
    loadJob?.cancel()

    stopNativePlayer()
    pauseWebPlayer()

    // Instant offline playback path for tracks auto-cached after multiple plays
    if (!videoMode) {
      val cachedFile = audioCacheManager.getOfflineFile(videoId)
      if (cachedFile != null) {
        startOfflineFilePlayback(videoId, cachedFile, startAtSeconds)
        return
      }
    }

    if (videoMode) {
      startWatchWebPlayback(videoId, startAtSeconds)
    } else {
      onPlaybackStateChanged(false, true)
      loadJob =
        scope.launch {
          when (val resolved = streamProxyServer.resolvePlaybackTarget(videoId, quality)) {
            is ProxyResolutionResult.LocalFile -> {
              if (activeVideoId != videoId || useWebVideoMode) return@launch
              startOfflineFilePlayback(videoId, resolved.file, startAtSeconds)
            }
            is ProxyResolutionResult.ProxyStream -> {
              if (activeVideoId != videoId || useWebVideoMode) return@launch
              pendingWatchCandidates = listOf(resolved.primaryStream)
              startNativeUriPlayback(
                videoId = videoId,
                playbackUri = resolved.proxyUrl,
                confirmStream = resolved.primaryStream,
                isHls = false,
                startAtSeconds = startAtSeconds,
              )
            }
            is ProxyResolutionResult.DirectHls -> {
              if (activeVideoId != videoId || useWebVideoMode) return@launch
              pendingWatchCandidates = emptyList()
              startNativeUriPlayback(
                videoId = videoId,
                playbackUri = resolved.hlsUrl,
                confirmStream = resolved.primaryStream,
                isHls = true,
                startAtSeconds = startAtSeconds,
              )
            }
            ProxyResolutionResult.Unavailable -> {
              if (activeVideoId != videoId || useWebVideoMode) return@launch
              Log.i(TAG, "Proxy resolver yielded none for $videoId; starting Watch DOM fallback")
              startWatchWebPlayback(videoId, startAtSeconds)
            }
          }
        }
    }
  }

  fun setVideoMode(
    videoMode: Boolean,
    currentPositionSec: Long,
    quality: AudioQuality = currentQuality,
  ) {
    val vid = activeVideoId ?: return
    if (useWebVideoMode == videoMode) return
    loadTrack(vid, videoMode = videoMode, quality = quality, startAtSeconds = currentPositionSec)
  }

  fun updateQuality(quality: AudioQuality, currentPositionSec: Long) {
    currentQuality = quality
    val vid = activeVideoId ?: return
    if (!useWebVideoMode && !activeIsOfflineHit) {
      loadTrack(vid, videoMode = false, quality = quality, startAtSeconds = currentPositionSec)
    }
  }

  fun play() {
    if (activeModeIsNative) {
      exoPlayer.playWhenReady = true
      exoPlayer.play()
      startTicker()
    } else {
      evalJs("if (window.sonoraPlay) window.sonoraPlay();")
    }
  }

  fun pause() {
    if (activeModeIsNative) {
      exoPlayer.pause()
      stopTicker()
      onPlaybackStateChanged(false, false)
    } else {
      evalJs("if (window.sonoraPause) window.sonoraPause();")
    }
  }

  fun seekTo(seconds: Long) {
    if (activeModeIsNative) {
      pendingSeekSec = seconds
      exoPlayer.seekTo(seconds * 1000L)
      val rawDur = exoPlayer.duration
      val durSec = if (rawDur != C.TIME_UNSET && rawDur > 0L) rawDur / 1000L else 0L
      onProgressChanged(seconds, durSec)
    } else {
      evalJs("if (window.sonoraSeek) window.sonoraSeek($seconds);")
    }
  }

  fun destroy() {
    loadJob?.cancel()
    prefetchJob?.cancel()
    scope.cancel()
    streamProxyServer.stop()
    stopNativePlayer()
    exoPlayer.release()
    webViewInstance?.let { wv ->
      wv.evaluateJavascript("if (window.sonoraStop) window.sonoraStop();", null)
      wv.removeJavascriptInterface("SonoraBridge")
      wv.stopLoading()
      wv.destroy()
      webViewInstance = null
    }
    onWebStageActiveChanged(false)
  }

  private fun startOfflineFilePlayback(
    videoId: String,
    file: File,
    startAtSeconds: Long,
  ) {
    activeModeIsNative = true
    activeIsOfflineHit = true
    pendingSeekSec = startAtSeconds
    onWebStageActiveChanged(false)
    onPlaybackStateChanged(false, true)
    Log.i(TAG, "Playing offline cached audio for $videoId (${file.length()} bytes)")

    mainHandler.post {
      if (activeVideoId != videoId || !activeModeIsNative) return@post
      val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
      exoPlayer.setMediaItem(mediaItem)
      if (startAtSeconds > 0L) {
        exoPlayer.seekTo(startAtSeconds * 1000L)
      }
      exoPlayer.playWhenReady = true
      exoPlayer.prepare()
    }
  }

  private fun startNativeUriPlayback(
    videoId: String,
    playbackUri: String,
    confirmStream: ExtractedAudioStream,
    isHls: Boolean,
    startAtSeconds: Long,
  ) {
    activeModeIsNative = true
    activeIsOfflineHit = false
    activeStreamForConfirm = confirmStream
    pendingSeekSec = startAtSeconds
    onWebStageActiveChanged(false)
    onPlaybackStateChanged(false, true)
    Log.i(TAG, "Starting ExoPlayer via ${confirmStream.sourceLabel} ($playbackUri) for $videoId")

    mainHandler.post {
      if (activeVideoId != videoId || !activeModeIsNative) return@post
      // Keep ExoPlayer User-Agent & headers strictly indistinguishable from a standard browser
      okHttpDataSourceFactory.setUserAgent(YouTubeStreamExtractor.STANDARD_BROWSER_USER_AGENT)
      okHttpDataSourceFactory.setDefaultRequestProperties(BROWSER_DEFAULT_HEADERS)

      val mediaItemBuilder = MediaItem.Builder().setUri(playbackUri)
      if (isHls) {
        mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
      }
      exoPlayer.setMediaItem(mediaItemBuilder.build())
      if (startAtSeconds > 0L) {
        exoPlayer.seekTo(startAtSeconds * 1000L)
      }
      exoPlayer.playWhenReady = true
      exoPlayer.prepare()
    }
  }

  private fun confirmPlaybackOnce() {
    if (confirmedForCurrentLoad) return
    val vid = activeVideoId ?: return
    confirmedForCurrentLoad = true
    val stream = activeStreamForConfirm
    onPlaybackConfirmed(
      vid,
      stream?.cacheableDirectUrl,
      stream?.headers ?: emptyMap(),
      stream?.isHls == true && stream.cacheableDirectUrl == null,
      activeIsOfflineHit,
    )
  }

  private fun startWatchWebPlayback(videoId: String, startAtSeconds: Long) {
    activeModeIsNative = false
    pendingSeekSec = startAtSeconds
    onWebStageActiveChanged(true)
    onPlaybackStateChanged(false, true)
    mainHandler.post {
      runCatching {
          val wv = getOrCreateWebView()
          wv.onResume()
          wv.resumeTimers()
          val tParam = if (startAtSeconds > 0L) "&t=${startAtSeconds}s" else ""
          wv.loadUrl("https://m.youtube.com/watch?v=$videoId&hl=es$tParam")
        }
        .onFailure { err ->
          Log.e(TAG, "WebView fallback unavailable", err)
          onWebStageActiveChanged(false)
          onPlaybackStateChanged(false, false)
          onPlaybackError("No se pudo iniciar el reproductor web en este dispositivo.", false)
        }
    }
  }

  private fun injectWatchDomBridge(view: WebView?) {
    val startSeek = pendingSeekSec
    val js =
      """
      (function() {
        var styleId = '__sonora_style';
        if (!document.getElementById(styleId)) {
          var style = document.createElement('style');
          style.id = styleId;
          style.textContent = 'ytm-mobile-topbar-renderer, ytm-pivot-bar-renderer, .watch-below-the-player { opacity: 0.01 !important; pointer-events: none !important; } body, ytm-app { background: #0E0D13 !important; }';
          (document.head || document.documentElement).appendChild(style);
        }

        if (window.__sonoraTimer) clearInterval(window.__sonoraTimer);
        var seekApplied = false;
        var lastEndedId = '';

        function attachToVideo() {
          var v = document.querySelector('video');
          if (!v) return;
          if (!v.__sonoraBound) {
            v.__sonoraBound = true;
            v.addEventListener('play', function() {
              if (!document.querySelector('.ad-showing')) {
                SonoraBridge.onStateChange(1);
              }
            });
            v.addEventListener('pause', function() {
              if (!document.querySelector('.ad-showing')) {
                SonoraBridge.onStateChange(2);
              }
            });
            v.addEventListener('waiting', function() {
              SonoraBridge.onStateChange(3);
            });
            v.addEventListener('ended', function() {
              if (!document.querySelector('.ad-showing') && lastEndedId !== location.href) {
                lastEndedId = location.href;
                SonoraBridge.onStateChange(0);
              }
            });
          }

          var adShowing = document.querySelector('.ad-showing');
          if (adShowing) {
            v.muted = true;
            v.playbackRate = 16;
            var skipBtn = document.querySelector('.ytp-skip-ad-button, .ytp-ad-skip-button');
            if (skipBtn) skipBtn.click();
          } else {
            var unmuteBtn = document.querySelector('.ytm-mute-button, [aria-label*="Unmute"], [aria-label*="Desactivar silencio"]');
            if (unmuteBtn && v.muted) unmuteBtn.click();
            if (v.muted) v.muted = false;
            if (v.playbackRate !== 1) v.playbackRate = 1;
            if (!seekApplied && $startSeek > 0 && v.duration > $startSeek) {
              v.currentTime = $startSeek;
              seekApplied = true;
            }
            if (v.paused && v.readyState >= 2) {
              v.play().catch(function(){});
            }
            SonoraBridge.onProgress(v.currentTime || 0, v.duration || 0);
            if (!v.paused) {
              SonoraBridge.onStateChange(1);
            }
          }
        }

        window.sonoraPlay = function() {
          var v = document.querySelector('video');
          if (v) { v.muted = false; v.play().catch(function(){}); }
        };
        window.sonoraPause = function() {
          var v = document.querySelector('video');
          if (v) v.pause();
        };
        window.sonoraSeek = function(sec) {
          var v = document.querySelector('video');
          if (v) v.currentTime = sec;
        };
        window.sonoraStop = function() {
          var v = document.querySelector('video');
          if (v) v.pause();
        };

        window.__sonoraTimer = setInterval(attachToVideo, 500);
        attachToVideo();
      })();
      """
        .trimIndent()
    view?.evaluateJavascript(js, null)
  }

  private fun stopNativePlayer() {
    stopTicker()
    activeModeIsNative = false
    mainHandler.post {
      exoPlayer.stop()
      exoPlayer.clearMediaItems()
    }
  }

  private fun pauseWebPlayer() {
    onWebStageActiveChanged(false)
    val wv = webViewInstance ?: return
    mainHandler.post {
      wv.evaluateJavascript("if (window.sonoraPause) window.sonoraPause();", null)
    }
  }

  private fun startTicker() {
    mainHandler.removeCallbacks(nativeProgressTicker)
    mainHandler.post(nativeProgressTicker)
  }

  private fun stopTicker() {
    mainHandler.removeCallbacks(nativeProgressTicker)
  }

  private fun evalJs(script: String) {
    val wv = webViewInstance ?: return
    mainHandler.post { wv.evaluateJavascript(script, null) }
  }

  private inner class PlayerBridge {
    @JavascriptInterface
    fun onStateChange(state: Int) {
      mainHandler.post {
        if (activeModeIsNative) return@post
        when (state) {
          1 -> {
            confirmPlaybackOnce()
            onPlaybackStateChanged(true, false) // PLAYING
          }
          2 -> onPlaybackStateChanged(false, false) // PAUSED
          3 -> onPlaybackStateChanged(false, true) // BUFFERING
          0 -> {
            onPlaybackStateChanged(false, false)
            onTrackEnded()
          }
          else -> Unit
        }
      }
    }

    @JavascriptInterface
    fun onProgress(currentSec: Double, durationSec: Double) {
      mainHandler.post {
        if (activeModeIsNative) return@post
        val current = currentSec.toLong().coerceAtLeast(0L)
        val duration = durationSec.toLong().coerceAtLeast(0L)
        onProgressChanged(current, duration)
      }
    }
  }

  companion object {
    private const val TAG = "YouTubePlayerEngine"
    private const val MOBILE_CHROME_UA =
      "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"

    private val BROWSER_DEFAULT_HEADERS =
      mapOf(
        "User-Agent" to YouTubeStreamExtractor.STANDARD_BROWSER_USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"134\", \"Google Chrome\";v=\"134\", \"Not:A-Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Windows\"",
      )
  }
}
