package com.example.player

import android.util.Log
import com.example.data.model.AudioQuality
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

data class ExtractedAudioStream(
  val url: String,
  val headers: Map<String, String>,
  val sourceLabel: String,
  val isHls: Boolean = false,
  val cacheableDirectUrl: String? = if (!isHls) url else null,
)

class YouTubeStreamExtractor(
  private val client: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(4, TimeUnit.SECONDS)
      .readTimeout(5, TimeUnit.SECONDS)
      .callTimeout(6, TimeUnit.SECONDS)
      .followRedirects(true)
      .followSslRedirects(true)
      .build(),
  private val innerTubeBaseUrl: String = "https://www.youtube.com",
  private val skipNetworkProbe: Boolean = false,
) {
  @Volatile private var cachedVisitorData: String? = null
  @Volatile private var cachedCipherOps: Pair<String, List<CipherOp>>? = null

  private data class CachedResult(
    val streams: List<ExtractedAudioStream>,
    val expiresAtMs: Long,
  )

  private val streamCache = ConcurrentHashMap<String, CachedResult>()

  suspend fun warmUp() {
    withContext(Dispatchers.IO) { getOrFetchVisitorData() }
  }

  fun getCachedStreams(videoId: String, quality: AudioQuality): List<ExtractedAudioStream>? {
    val key = "$videoId:${quality.name}"
    val entry = streamCache[key] ?: return null
    if (System.currentTimeMillis() < entry.expiresAtMs && entry.streams.isNotEmpty()) {
      return entry.streams
    }
    streamCache.remove(key)
    return null
  }

  suspend fun prefetch(videoId: String, quality: AudioQuality) {
    if (getCachedStreams(videoId, quality) != null) return
    runCatching { extractAudioStreams(videoId, quality) }
  }

  fun invalidateCache(videoId: String, quality: AudioQuality) {
    streamCache.remove("$videoId:${quality.name}")
  }

  suspend fun extractAudioStreams(
    videoId: String,
    quality: AudioQuality = AudioQuality.HIGH,
    forceRefresh: Boolean = false,
  ): List<ExtractedAudioStream> =
    withContext(Dispatchers.IO) {
      if (forceRefresh) {
        invalidateCache(videoId, quality)
      } else {
        getCachedStreams(videoId, quality)?.let { return@withContext it }
      }

      val visitorData = getOrFetchVisitorData()

      // Wave 1: Race the 3 fastest direct InnerTube clients in parallel
      val wave1 =
        raceExtractors(
          listOf(
            {
              tryInnerTubeClient(
                videoId = videoId,
                quality = quality,
                visitorData = visitorData,
                clientName = "ANDROID_VR",
                clientVersion = "1.61.48",
                userAgent =
                  "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                extraClientFields =
                  mapOf(
                    "androidSdkVersion" to 32,
                    "deviceMake" to "Oculus",
                    "deviceModel" to "Quest 3",
                    "osName" to "Android",
                    "osVersion" to "12L",
                  ),
                clientIdHeader = "28",
                sendOriginHeaders = false,
              )
            },
            {
              tryInnerTubeClient(
                videoId = videoId,
                quality = quality,
                visitorData = visitorData,
                clientName = "IOS",
                clientVersion = "20.10.4",
                userAgent =
                  "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X)",
                extraClientFields =
                  mapOf(
                    "deviceMake" to "Apple",
                    "deviceModel" to "iPhone16,2",
                    "osName" to "iPhone",
                    "osVersion" to "18.3.1.22D72",
                  ),
                clientIdHeader = "5",
                sendOriginHeaders = false,
              )
            },
            {
              tryInnerTubeClient(
                videoId = videoId,
                quality = quality,
                visitorData = visitorData,
                clientName = "ANDROID_TESTSUITE",
                clientVersion = "1.9",
                userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 11) gzip",
                extraClientFields =
                  mapOf(
                    "androidSdkVersion" to 30,
                    "osName" to "Android",
                    "osVersion" to "11",
                  ),
                clientIdHeader = "30",
                sendOriginHeaders = false,
              )
            },
          )
        )

      if (wave1.isNotEmpty()) {
        cacheStreams(videoId, quality, wave1)
        return@withContext wave1
      }

      // Wave 2: Race ANDROID_MUSIC + WatchPage (cipher decipherer) + top Piped/Invidious hosts in parallel
      val wave2Tasks = buildList<suspend () -> List<ExtractedAudioStream>> {
        add {
          tryInnerTubeClient(
            videoId = videoId,
            quality = quality,
            visitorData = visitorData,
            clientName = "ANDROID_MUSIC",
            clientVersion = "7.29.52",
            userAgent = "com.google.android.apps.youtube.music/7.29.52 (Linux; U; Android 14) gzip",
            extraClientFields =
              mapOf("androidSdkVersion" to 34, "osName" to "Android", "osVersion" to "14"),
            clientIdHeader = "21",
            sendOriginHeaders = false,
          )
        }
        add { tryWatchPageExtraction(videoId, quality) }
        PIPED_INSTANCES.take(2).forEach { host -> add { tryPipedHost(host, videoId, quality) } }
        INVIDIOUS_INSTANCES.take(2).forEach { host ->
          add { tryInvidiousHost(host, videoId, quality) }
        }
      }

      val wave2 = raceExtractors(wave2Tasks)
      if (wave2.isNotEmpty()) {
        cacheStreams(videoId, quality, wave2)
      }
      wave2
    }

  private fun cacheStreams(
    videoId: String,
    quality: AudioQuality,
    streams: List<ExtractedAudioStream>,
  ) {
    streamCache["$videoId:${quality.name}"] =
      CachedResult(
        streams = streams,
        expiresAtMs = System.currentTimeMillis() + STREAM_CACHE_TTL_MS,
      )
  }

  /**
   * Executes candidate extractors concurrently and returns immediately as soon as the first one
   * yields a verified stream, cancelling all remaining slower network calls.
   */
  private suspend fun raceExtractors(
    tasks: List<suspend () -> List<ExtractedAudioStream>>
  ): List<ExtractedAudioStream> = coroutineScope {
    val winnerChannel = Channel<List<ExtractedAudioStream>>(Channel.UNLIMITED)
    val fallbackPool = mutableListOf<ExtractedAudioStream>()

    val jobs =
      tasks.map { task ->
        launch(Dispatchers.IO) {
          val candidates = runCatching { task() }.getOrDefault(emptyList())
          if (candidates.isEmpty()) return@launch
          val verified = verifyFirstFast(candidates)
          if (verified.isNotEmpty()) {
            winnerChannel.trySend(verified)
          } else {
            synchronized(fallbackPool) {
              if (fallbackPool.size < 2) fallbackPool.addAll(candidates.take(1))
            }
          }
        }
      }

    val allDone = async(Dispatchers.IO) {
      jobs.forEach { it.join() }
      emptyList<ExtractedAudioStream>()
    }

    val winner = select<List<ExtractedAudioStream>> {
      winnerChannel.onReceive { it }
      allDone.onAwait { it }
    }

    jobs.forEach { it.cancel() }
    allDone.cancel()
    winnerChannel.close()

    if (winner.isNotEmpty()) winner
    else synchronized(fallbackPool) { fallbackPool.toList() }
  }

  private suspend fun OkHttpClient.executeCancellable(request: Request): Response =
    suspendCancellableCoroutine { cont ->
      val call = newCall(request)
      cont.invokeOnCancellation { runCatching { call.cancel() } }
      call.enqueue(
        object : Callback {
          override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
          }

          override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) {
              cont.resumeWith(Result.success(response))
            } else {
              runCatching { response.close() }
            }
          }
        }
      )
    }

  private suspend fun verifyFirstFast(
    candidates: List<ExtractedAudioStream>
  ): List<ExtractedAudioStream> {
    if (skipNetworkProbe) return candidates
    for (candidate in candidates) {
      if (probeStream(candidate)) {
        // Return the verified primary plus any unverified backup in case ExoPlayer needs a secondary
        val others = candidates.filterNot { it.url == candidate.url }.take(1)
        return listOf(candidate) + others
      }
    }
    return emptyList()
  }

  private suspend fun probeStream(stream: ExtractedAudioStream): Boolean {
    return runCatching {
        val reqBuilder = Request.Builder().url(stream.url).get()
        if (!stream.isHls) {
          reqBuilder.header("Range", "bytes=0-1")
        }
        stream.headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        client.executeCancellable(reqBuilder.build()).use { resp ->
          resp.code in 200..299 || resp.code == 206
        }
      }
      .getOrDefault(false)
  }

  private suspend fun getOrFetchVisitorData(): String? {
    cachedVisitorData?.let { return it }
    val fetched =
      runCatching {
          val payload =
            JSONObject().apply {
              put(
                "context",
                JSONObject().put(
                  "client",
                  JSONObject().apply {
                    put("clientName", "WEB")
                    put("clientVersion", "2.20250218.01.00")
                    put("hl", "es")
                    put("gl", "US")
                  },
                ),
              )
            }
          val req =
            Request.Builder()
              .url("$innerTubeBaseUrl/youtubei/v1/visitor_id?prettyPrint=false")
              .header("User-Agent", WEB_USER_AGENT)
              .header("Origin", innerTubeBaseUrl)
              .post(payload.toString().toRequestBody(JSON_MEDIA))
              .build()
          client.executeCancellable(req).use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val raw = resp.body?.string() ?: return@runCatching null
            JSONObject(raw)
              .optJSONObject("responseContext")
              ?.optString("visitorData", "")
              ?.takeIf { it.isNotBlank() }
          }
        }
        .getOrNull()

    if (!fetched.isNullOrBlank()) {
      cachedVisitorData = fetched
    }
    return fetched
  }

  private suspend fun tryInnerTubeClient(
    videoId: String,
    quality: AudioQuality,
    visitorData: String?,
    clientName: String,
    clientVersion: String,
    userAgent: String,
    extraClientFields: Map<String, Any>,
    clientIdHeader: String,
    sendOriginHeaders: Boolean,
  ): List<ExtractedAudioStream> {
    return runCatching {
        val clientObj =
          JSONObject().apply {
            put("clientName", clientName)
            put("clientVersion", clientVersion)
            put("hl", "es")
            put("gl", "US")
            if (!visitorData.isNullOrBlank()) {
              put("visitorData", visitorData)
            }
            extraClientFields.forEach { (k, v) -> put(k, v) }
          }
        val bodyJson =
          JSONObject().apply {
            put("context", JSONObject().put("client", clientObj))
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
          }

        val reqBuilder =
          Request.Builder()
            .url("$innerTubeBaseUrl/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", userAgent)
            .header("X-YouTube-Client-Name", clientIdHeader)
            .header("X-YouTube-Client-Version", clientVersion)
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA))
        if (sendOriginHeaders) {
          reqBuilder.header("Origin", innerTubeBaseUrl)
          reqBuilder.header("Cookie", "CONSENT=YES+cb.20210328-17-p0.en+FX+678")
        }
        if (!visitorData.isNullOrBlank()) {
          reqBuilder.header("X-Goog-Visitor-Id", visitorData)
        }

        client.executeCancellable(reqBuilder.build()).use { resp ->
          if (!resp.isSuccessful) return@runCatching emptyList()
          val raw = resp.body?.string() ?: return@runCatching emptyList()
          val root = JSONObject(raw)
          val playability =
            root.optJSONObject("playabilityStatus")?.optString("status", "").orEmpty()
          if (playability != "OK" && playability.isNotEmpty()) {
            Log.d(TAG, "$clientName playability=$playability for $videoId")
            return@runCatching emptyList()
          }
          val streamingData = root.optJSONObject("streamingData") ?: return@runCatching emptyList()

          val headers = mapOf("User-Agent" to userAgent)
          val directAudio =
            pickBestAudioUrl(streamingData.optJSONArray("adaptiveFormats"), quality, null)
          val progressiveMp4 =
            pickBestProgressiveUrl(streamingData.optJSONArray("formats"), null)
          val hlsUrl =
            streamingData.optString("hlsManifestUrl", "").takeIf { it.isNotBlank() }

          val out = mutableListOf<ExtractedAudioStream>()
          if (!directAudio.isNullOrBlank()) {
            out.add(
              ExtractedAudioStream(
                url = directAudio,
                headers = headers,
                sourceLabel = "Direct ($clientName Audio)",
                isHls = false,
                cacheableDirectUrl = directAudio,
              )
            )
          }
          if (!progressiveMp4.isNullOrBlank()) {
            out.add(
              ExtractedAudioStream(
                url = progressiveMp4,
                headers = headers,
                sourceLabel = "Direct ($clientName MP4)",
                isHls = false,
                cacheableDirectUrl = directAudio ?: progressiveMp4,
              )
            )
          }
          if (!hlsUrl.isNullOrBlank()) {
            out.add(
              ExtractedAudioStream(
                url = hlsUrl,
                headers = headers,
                sourceLabel = "Direct ($clientName HLS)",
                isHls = true,
                cacheableDirectUrl = directAudio ?: progressiveMp4,
              )
            )
          }
          out
        }
      }
      .getOrDefault(emptyList())
  }

  private suspend fun tryWatchPageExtraction(
    videoId: String,
    quality: AudioQuality,
  ): List<ExtractedAudioStream> {
    return runCatching {
        val req =
          Request.Builder()
            .url("$innerTubeBaseUrl/watch?v=$videoId&bpctr=9999999999&has_verified=1")
            .header("User-Agent", WEB_USER_AGENT)
            .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            .header("Cookie", "CONSENT=YES+cb.20210328-17-p0.en+FX+678")
            .get()
            .build()

        val html =
          client.executeCancellable(req).use { resp ->
            if (!resp.isSuccessful) return@runCatching emptyList()
            resp.body?.string() ?: return@runCatching emptyList()
          }

        val playerJsonStr =
          extractBalancedJsonAfter(html, "ytInitialPlayerResponse = ") ?: return@runCatching emptyList()
        val root = JSONObject(playerJsonStr)
        val playability = root.optJSONObject("playabilityStatus")?.optString("status", "").orEmpty()
        if (playability != "OK" && playability.isNotEmpty()) return@runCatching emptyList()

        val streamingData = root.optJSONObject("streamingData") ?: return@runCatching emptyList()
        val playerJsPath = PLAYER_JS_REGEX.find(html)?.groupValues?.getOrNull(1)
        val headers =
          mapOf(
            "User-Agent" to WEB_USER_AGENT,
            "Origin" to innerTubeBaseUrl,
            "Referer" to "$innerTubeBaseUrl/",
          )

        val out = mutableListOf<ExtractedAudioStream>()
        pickBestAudioUrl(streamingData.optJSONArray("adaptiveFormats"), quality, playerJsPath)?.let {
          out.add(
            ExtractedAudioStream(
              url = it,
              headers = headers,
              sourceLabel = "WatchStream Audio",
              cacheableDirectUrl = it,
            )
          )
        }
        pickBestProgressiveUrl(streamingData.optJSONArray("formats"), playerJsPath)?.let {
          out.add(
            ExtractedAudioStream(
              url = it,
              headers = headers,
              sourceLabel = "WatchStream MP4",
              cacheableDirectUrl = it,
            )
          )
        }
        out
      }
      .getOrDefault(emptyList())
  }

  private fun extractBalancedJsonAfter(source: String, marker: String): String? {
    val idx = source.indexOf(marker)
    if (idx == -1) return null
    val start = source.indexOf('{', idx + marker.length)
    if (start == -1) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until source.length) {
      val ch = source[i]
      if (inString) {
        if (escaped) {
          escaped = false
        } else if (ch == '\\') {
          escaped = true
        } else if (ch == '"') {
          inString = false
        }
      } else {
        when (ch) {
          '"' -> inString = true
          '{' -> depth++
          '}' -> {
            depth--
            if (depth == 0) {
              return source.substring(start, i + 1)
            }
          }
        }
      }
    }
    return null
  }

  private suspend fun resolveFormatUrl(formatObj: JSONObject, playerJsPath: String?): String? {
    val direct = formatObj.optString("url", "")
    if (direct.isNotBlank()) return direct

    val cipher =
      formatObj.optString("signatureCipher", "").ifBlank { formatObj.optString("cipher", "") }
    if (cipher.isBlank() || playerJsPath.isNullOrBlank()) return null

    val params = parseQueryParams(cipher)
    val baseUrl = params["url"] ?: return null
    val encryptedSig = params["s"] ?: return null
    val sigParamName = params["sp"] ?: "sig"

    val ops = getOrParseCipherOps(playerJsPath) ?: return null
    val deciphered = applyCipherOps(encryptedSig, ops)
    val sep = if (baseUrl.contains("?")) "&" else "?"
    return "$baseUrl$sep$sigParamName=${URLEncoder.encode(deciphered, "UTF-8")}"
  }

  private fun parseQueryParams(query: String): Map<String, String> {
    val map = mutableMapOf<String, String>()
    for (pair in query.split("&")) {
      val eq = pair.indexOf('=')
      if (eq > 0) {
        val k = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
        val v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
        map[k] = v
      }
    }
    return map
  }

  private suspend fun getOrParseCipherOps(playerJsPath: String): List<CipherOp>? {
    cachedCipherOps?.let { (cachedPath, ops) ->
      if (cachedPath == playerJsPath) return ops
    }
    val jsUrl =
      if (playerJsPath.startsWith("http")) playerJsPath else "$innerTubeBaseUrl$playerJsPath"
    val js =
      runCatching {
          val req = Request.Builder().url(jsUrl).header("User-Agent", WEB_USER_AGENT).get().build()
          client.executeCancellable(req).use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            resp.body?.string()
          }
        }
        .getOrNull() ?: return null

    val ops = parseCipherOpsFromPlayerJs(js)
    if (ops.isNotEmpty()) {
      cachedCipherOps = playerJsPath to ops
      return ops
    }
    return null
  }

  internal fun parseCipherOpsFromPlayerJs(js: String): List<CipherOp> {
    val sigFuncMatch =
      Regex("""\b([a-zA-Z0-9_$]+)=function\(a\)\{a=a\.split\(""\);([^}]+)return a\.join\(""\)\}""")
        .find(js) ?: return emptyList()
    val body = sigFuncMatch.groupValues[2]
    val firstCall = Regex("""([a-zA-Z0-9_$]+)\.[a-zA-Z0-9_$]+\(a(?:,\d+)?\)""").find(body)
    val helperName = firstCall?.groupValues?.get(1) ?: return emptyList()

    val helperObjMatch =
      Regex("""var ${Regex.escape(helperName)}=\{([\s\S]{20,500}?)\};""").find(js)
        ?: return emptyList()
    val helperBody = helperObjMatch.groupValues[1]

    val reverseMethod =
      Regex("""([a-zA-Z0-9_$]+):function\(a(?:,[a-zA-Z0-9_$]+)?\)\{a\.reverse\(\)\}""")
        .find(helperBody)
        ?.groupValues
        ?.get(1)
    val spliceMethod =
      Regex("""([a-zA-Z0-9_$]+):function\(a,b\)\{a\.splice\(0,b\)\}""").find(helperBody)?.groupValues?.get(1)
    val swapMethod =
      Regex("""([a-zA-Z0-9_$]+):function\(a,b\)\{var c=a\[0\];a\[0\]=a\[b%a\.length\];a\[b%a\.length\]=c\}""")
        .find(helperBody)
        ?.groupValues
        ?.get(1)

    val ops = mutableListOf<CipherOp>()
    val stepRegex = Regex("""${Regex.escape(helperName)}\.([a-zA-Z0-9_$]+)\(a(?:,(\d+))?\)""")
    for (m in stepRegex.findAll(body)) {
      val method = m.groupValues[1]
      val arg = m.groupValues[2].toIntOrNull() ?: 0
      when (method) {
        reverseMethod -> ops.add(CipherOp.Reverse)
        spliceMethod -> ops.add(CipherOp.Splice(arg))
        swapMethod -> ops.add(CipherOp.Swap(arg))
      }
    }
    return ops
  }

  internal fun applyCipherOps(sig: String, ops: List<CipherOp>): String {
    val chars = sig.toMutableList()
    for (op in ops) {
      when (op) {
        CipherOp.Reverse -> chars.reverse()
        is CipherOp.Splice -> {
          val count = op.count.coerceIn(0, chars.size)
          repeat(count) { if (chars.isNotEmpty()) chars.removeAt(0) }
        }
        is CipherOp.Swap -> {
          if (chars.isNotEmpty()) {
            val idx = op.index % chars.size
            val tmp = chars[0]
            chars[0] = chars[idx]
            chars[idx] = tmp
          }
        }
      }
    }
    return chars.joinToString("")
  }

  private suspend fun tryPipedHost(
    host: String,
    videoId: String,
    quality: AudioQuality,
  ): List<ExtractedAudioStream> {
    return runCatching {
        val req =
          Request.Builder()
            .url("$host/streams/$videoId")
            .header("User-Agent", WEB_USER_AGENT)
            .get()
            .build()
        client.executeCancellable(req).use { resp ->
          if (!resp.isSuccessful) return@runCatching emptyList()
          val root = JSONObject(resp.body?.string() ?: return@runCatching emptyList())
          val audioStreams = root.optJSONArray("audioStreams") ?: return@runCatching emptyList()
          val candidates = mutableListOf<AudioCandidate>()
          for (i in 0 until audioStreams.length()) {
            val obj = audioStreams.optJSONObject(i) ?: continue
            val url = obj.optString("url", "")
            if (url.isBlank()) continue
            val mime = obj.optString("mimeType", "")
            val bitrate = obj.optInt("bitrate", 0)
            candidates.add(AudioCandidate(url = url, mime = mime, bitrate = bitrate))
          }
          val out = mutableListOf<ExtractedAudioStream>()
          val picked = selectCandidate(candidates, quality)
          if (picked != null) {
            out.add(
              ExtractedAudioStream(
                url = picked.url,
                headers = mapOf("User-Agent" to WEB_USER_AGENT),
                sourceLabel = "Piped",
                cacheableDirectUrl = picked.url,
              )
            )
          }
          root.optString("hls", "").takeIf { it.isNotBlank() }?.let { hls ->
            out.add(
              ExtractedAudioStream(
                url = hls,
                headers = mapOf("User-Agent" to WEB_USER_AGENT),
                sourceLabel = "Piped HLS",
                isHls = true,
                cacheableDirectUrl = picked?.url,
              )
            )
          }
          out
        }
      }
      .getOrDefault(emptyList())
  }

  private suspend fun tryInvidiousHost(
    host: String,
    videoId: String,
    quality: AudioQuality,
  ): List<ExtractedAudioStream> {
    return runCatching {
        val req =
          Request.Builder()
            .url("$host/api/v1/videos/$videoId")
            .header("User-Agent", WEB_USER_AGENT)
            .get()
            .build()
        client.executeCancellable(req).use { resp ->
          if (!resp.isSuccessful) return@runCatching emptyList()
          val root = JSONObject(resp.body?.string() ?: return@runCatching emptyList())
          val formats = root.optJSONArray("adaptiveFormats") ?: return@runCatching emptyList()
          val candidates = mutableListOf<AudioCandidate>()
          for (i in 0 until formats.length()) {
            val obj = formats.optJSONObject(i) ?: continue
            val url = obj.optString("url", "")
            val mime = obj.optString("type", "")
            if (url.isBlank() || !mime.startsWith("audio/")) continue
            val bitrate = obj.optString("bitrate", "0").toIntOrNull() ?: 0
            candidates.add(AudioCandidate(url = url, mime = mime, bitrate = bitrate))
          }
          selectCandidate(candidates, quality)?.let { picked ->
            listOf(
              ExtractedAudioStream(
                url = picked.url,
                headers = mapOf("User-Agent" to WEB_USER_AGENT),
                sourceLabel = "Invidious",
                cacheableDirectUrl = picked.url,
              )
            )
          } ?: emptyList()
        }
      }
      .getOrDefault(emptyList())
  }

  private suspend fun pickBestAudioUrl(
    formats: JSONArray?,
    quality: AudioQuality,
    playerJsPath: String?,
  ): String? {
    if (formats == null) return null
    val candidates = mutableListOf<AudioCandidate>()
    for (i in 0 until formats.length()) {
      val f = formats.optJSONObject(i) ?: continue
      val mime = f.optString("mimeType", "")
      if (!mime.startsWith("audio/")) continue
      val url = resolveFormatUrl(f, playerJsPath) ?: continue
      val bitrate = f.optInt("bitrate", 0)
      candidates.add(AudioCandidate(url = url, mime = mime, bitrate = bitrate))
    }
    return selectCandidate(candidates, quality)?.url
  }

  private fun selectCandidate(
    candidates: List<AudioCandidate>,
    quality: AudioQuality,
  ): AudioCandidate? {
    if (candidates.isEmpty()) return null
    val mp4s = candidates.filter { it.mime.startsWith("audio/mp4") }
    val pool = if (mp4s.isNotEmpty()) mp4s else candidates
    val withinCap = pool.filter { it.bitrate in 1..quality.maxBitrateBps }
    return if (withinCap.isNotEmpty()) {
      when (quality) {
        AudioQuality.DATA_SAVER -> withinCap.minByOrNull { it.bitrate }
        else -> withinCap.maxByOrNull { it.bitrate }
      }
    } else {
      when (quality) {
        AudioQuality.HIGH -> pool.maxByOrNull { it.bitrate }
        AudioQuality.BALANCED, AudioQuality.DATA_SAVER -> pool.minByOrNull { it.bitrate }
      }
    }
  }

  private suspend fun pickBestProgressiveUrl(formats: JSONArray?, playerJsPath: String?): String? {
    if (formats == null) return null
    for (i in 0 until formats.length()) {
      val f = formats.optJSONObject(i) ?: continue
      val url = resolveFormatUrl(f, playerJsPath)
      if (!url.isNullOrBlank()) return url
    }
    return null
  }

  internal sealed interface CipherOp {
    data object Reverse : CipherOp
    data class Splice(val count: Int) : CipherOp
    data class Swap(val index: Int) : CipherOp
  }

  private data class AudioCandidate(val url: String, val mime: String, val bitrate: Int)

  companion object {
    const val STANDARD_BROWSER_USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
    private const val TAG = "YouTubeStreamExtractor"
    private const val STREAM_CACHE_TTL_MS = 15 * 60 * 1000L
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val PLAYER_JS_REGEX = Regex("""(/s/player/[a-zA-Z0-9_-]+/[^"'\s]+/base\.js)""")
    private const val WEB_USER_AGENT = STANDARD_BROWSER_USER_AGENT

    private val PIPED_INSTANCES =
      listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://pipedapi.ducks.party",
      )

    val INVIDIOUS_INSTANCES =
      listOf(
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
      )
  }
}
