package com.example.player

import android.util.Log
import com.example.data.cache.AudioCacheManager
import com.example.data.model.AudioQuality
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Ensures ExoPlayer HTTP traffic presents headers indistinguishable from a standard desktop Chrome
 * web browser (removing ExoPlayer/AndroidXMedia3/OkHttp fingerprints).
 */
class BrowserIdentityInterceptor(
  private val browserUserAgent: String = YouTubeStreamExtractor.STANDARD_BROWSER_USER_AGENT,
) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val original = chain.request()
    val builder =
      original
        .newBuilder()
        .header("User-Agent", browserUserAgent)
        .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
        .header("Sec-Ch-Ua", "\"Chromium\";v=\"134\", \"Google Chrome\";v=\"134\", \"Not:A-Brand\";v=\"24\"")
        .header("Sec-Ch-Ua-Mobile", "?0")
        .header("Sec-Ch-Ua-Platform", "\"Windows\"")
        .header("Sec-Fetch-Dest", "audio")
        .header("Sec-Fetch-Mode", "cors")
        .header("Sec-Fetch-Site", "cross-site")

    if (original.header("Accept").isNullOrBlank()) {
      builder.header("Accept", "*/*")
    }
    return chain.proceed(builder.build())
  }
}

sealed interface ProxyResolutionResult {
  data class LocalFile(val file: File) : ProxyResolutionResult

  data class ProxyStream(
    val proxyUrl: String,
    val primaryStream: ExtractedAudioStream,
  ) : ProxyResolutionResult

  data class DirectHls(
    val hlsUrl: String,
    val primaryStream: ExtractedAudioStream,
  ) : ProxyResolutionResult

  data object Unavailable : ProxyResolutionResult
}

/**
 * Loopback URL resolution proxy (127.0.0.1):
 * - Avoids YouTube /embed/ IFrame restrictions (Error 150) entirely by resolving direct media URLs.
 * - Handles upstream HTTP redirects, Range requests (HTTP 206), and automatic candidate failover
 *   + transparent URL re-resolution if an upstream stream expires (403/410) mid-playback.
 */
class LocalStreamProxyServer(
  private val streamExtractor: YouTubeStreamExtractor,
  private val audioCacheManager: AudioCacheManager? = null,
  private val upstreamClient: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(6, TimeUnit.SECONDS)
      .readTimeout(15, TimeUnit.SECONDS)
      .followRedirects(true)
      .followSslRedirects(true)
      .build(),
) {
  private val running = AtomicBoolean(false)
  private var serverSocket: ServerSocket? = null
  private var acceptExecutor: ExecutorService? = null
  private var workerExecutor: ExecutorService? = null
  private val candidateStore = ConcurrentHashMap<String, List<ExtractedAudioStream>>()

  @Volatile
  var port: Int = -1
    private set

  @Synchronized
  fun start(): Int {
    if (running.get() && port > 0) return port
    val socket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    serverSocket = socket
    port = socket.localPort
    running.set(true)

    val acceptPool = Executors.newSingleThreadExecutor { r ->
      Thread(r, "sonora-proxy-accept").apply { isDaemon = true }
    }
    val workers = Executors.newCachedThreadPool { r ->
      Thread(r, "sonora-proxy-worker").apply { isDaemon = true }
    }
    acceptExecutor = acceptPool
    workerExecutor = workers

    acceptPool.execute {
      while (running.get() && !socket.isClosed) {
        val clientSocket = runCatching { socket.accept() }.getOrNull() ?: break
        workers.execute { handleClientSocket(clientSocket) }
      }
    }
    return port
  }

  @Synchronized
  fun stop() {
    running.set(false)
    port = -1
    runCatching { serverSocket?.close() }
    serverSocket = null
    acceptExecutor?.shutdownNow()
    workerExecutor?.shutdownNow()
    acceptExecutor = null
    workerExecutor = null
    candidateStore.clear()
  }

  suspend fun resolvePlaybackTarget(
    videoId: String,
    quality: AudioQuality = AudioQuality.HIGH,
  ): ProxyResolutionResult {
    audioCacheManager?.getOfflineFile(videoId)?.let { cachedFile ->
      return ProxyResolutionResult.LocalFile(cachedFile)
    }

    val streams = streamExtractor.extractAudioStreams(videoId, quality)
    if (streams.isEmpty()) return ProxyResolutionResult.Unavailable

    candidateStore["$videoId:${quality.name}"] = streams
    val direct = streams.firstOrNull { !it.isHls }
    if (direct != null) {
      val activePort = start()
      val proxyUrl = "http://127.0.0.1:$activePort/stream?v=$videoId&q=${quality.name}"
      return ProxyResolutionResult.ProxyStream(proxyUrl = proxyUrl, primaryStream = direct)
    }

    val hls = streams.first()
    return ProxyResolutionResult.DirectHls(hlsUrl = hls.url, primaryStream = hls)
  }

  private fun handleClientSocket(socket: Socket) {
    socket.use { client ->
      runCatching {
          client.soTimeout = 15_000
          val input = BufferedInputStream(client.getInputStream())
          val output = BufferedOutputStream(client.getOutputStream())

          val requestHead = readHttpHead(input) ?: return
          val lines = requestHead.split("\r\n")
          val requestLine = lines.firstOrNull()?.split(" ") ?: return
          if (requestLine.size < 2) {
            writeSimpleStatus(output, 400, "Bad Request")
            return
          }

          val method = requestLine[0].uppercase()
          val rawTarget = requestLine[1]
          if (method != "GET" && method != "HEAD") {
            writeSimpleStatus(output, 405, "Method Not Allowed")
            return
          }

          val path = rawTarget.substringBefore("?")
          if (path == "/health") {
            val payload = """{"status":"ok"}""".toByteArray()
            val header =
              "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${payload.size}\r\n" +
                "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            if (method == "GET") output.write(payload)
            output.flush()
            return
          }

          if (path != "/stream") {
            writeSimpleStatus(output, 404, "Not Found")
            return
          }

          val headersMap = parseRequestHeaders(lines.drop(1))
          val queryMap = parseQuery(rawTarget.substringAfter("?", ""))
          val videoId = queryMap["v"].orEmpty()
          if (videoId.isBlank()) {
            writeSimpleStatus(output, 400, "Bad Request")
            return
          }
          val quality =
            runCatching { AudioQuality.valueOf(queryMap["q"] ?: AudioQuality.HIGH.name) }
              .getOrDefault(AudioQuality.HIGH)
          val rangeHeader = headersMap["range"]

          // 1. Offline file shortcut if cached on disk
          audioCacheManager?.getOfflineFile(videoId)?.let { file ->
            serveLocalFile(file, rangeHeader, method == "HEAD", output)
            return
          }

          // 2. Proxy through resolved upstream candidates with automatic failover & refresh
          val initialCandidates =
            candidateStore["$videoId:${quality.name}"]?.filter { !it.isHls }
              ?: runBlocking { streamExtractor.extractAudioStreams(videoId, quality) }
                .also { candidateStore["$videoId:${quality.name}"] = it }
                .filter { !it.isHls }

          if (tryStreamCandidates(initialCandidates, rangeHeader, method == "HEAD", output)) {
            return
          }

          // Upstream URLs may have expired or returned 403/410; force one fresh resolution
          val refreshedCandidates =
            runBlocking {
                streamExtractor.extractAudioStreams(videoId, quality, forceRefresh = true)
              }
              .also { candidateStore["$videoId:${quality.name}"] = it }
              .filter { !it.isHls }

          if (!tryStreamCandidates(refreshedCandidates, rangeHeader, method == "HEAD", output)) {
            writeSimpleStatus(output, 502, "Bad Gateway")
          }
        }
        .onFailure { err -> Log.d(TAG, "Proxy socket closed: ${err.message}") }
    }
  }

  private fun tryStreamCandidates(
    candidates: List<ExtractedAudioStream>,
    rangeHeader: String?,
    headOnly: Boolean,
    output: BufferedOutputStream,
  ): Boolean {
    for (candidate in candidates) {
      val reqBuilder =
        Request.Builder()
          .url(candidate.url)
          .header("User-Agent", YouTubeStreamExtractor.STANDARD_BROWSER_USER_AGENT)
          .header("Accept", "*/*")
          .header("Accept-Encoding", "identity")
      candidate.headers.forEach { (k, v) ->
        if (!k.equals("User-Agent", ignoreCase = true)) {
          reqBuilder.header(k, v)
        }
      }
      if (!rangeHeader.isNullOrBlank()) {
        reqBuilder.header("Range", rangeHeader)
      }
      if (headOnly) {
        reqBuilder.head()
      } else {
        reqBuilder.get()
      }

      val served =
        runCatching {
            upstreamClient.newCall(reqBuilder.build()).execute().use { resp ->
              if (resp.code !in 200..299 && resp.code != 206) {
                return@use false
              }
              val body = resp.body
              val contentType = resp.header("Content-Type") ?: "audio/mp4"
              val contentLength = resp.header("Content-Length")?.toLongOrNull() ?: body?.contentLength() ?: -1L
              val contentRange = resp.header("Content-Range")
              val statusLine =
                if (resp.code == 206) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK"

              val headerSb =
                StringBuilder()
                  .append(statusLine)
                  .append("\r\n")
                  .append("Content-Type: ")
                  .append(contentType)
                  .append("\r\n")
                  .append("Accept-Ranges: bytes\r\n")
                  .append("Cache-Control: no-store\r\n")
                  .append("Connection: close\r\n")

              if (contentLength >= 0L) {
                headerSb.append("Content-Length: ").append(contentLength).append("\r\n")
              }
              if (!contentRange.isNullOrBlank()) {
                headerSb.append("Content-Range: ").append(contentRange).append("\r\n")
              }
              headerSb.append("\r\n")

              output.write(headerSb.toString().toByteArray())
              if (!headOnly && body != null) {
                val buffer = ByteArray(16 * 1024)
                body.byteStream().use { upstreamIn ->
                  while (true) {
                    val read = upstreamIn.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                  }
                }
              }
              output.flush()
              true
            }
          }
          .getOrDefault(false)

      if (served) return true
    }
    return false
  }

  private fun serveLocalFile(
    file: File,
    rangeHeader: String?,
    headOnly: Boolean,
    output: BufferedOutputStream,
  ) {
    val totalLength = file.length()
    val parsedRange = parseByteRange(rangeHeader, totalLength)
    if (rangeHeader != null && parsedRange == null) {
      val header =
        "HTTP/1.1 416 Range Not Satisfiable\r\n" +
          "Content-Range: bytes */$totalLength\r\n" +
          "Connection: close\r\n\r\n"
      output.write(header.toByteArray())
      output.flush()
      return
    }

    val start = parsedRange?.first ?: 0L
    val end = parsedRange?.second ?: (totalLength - 1L).coerceAtLeast(0L)
    val contentLength = if (totalLength == 0L) 0L else (end - start + 1L)
    val isPartial = parsedRange != null
    val statusLine = if (isPartial) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK"

    val headerSb =
      StringBuilder()
        .append(statusLine)
        .append("\r\n")
        .append("Content-Type: audio/mp4\r\n")
        .append("Accept-Ranges: bytes\r\n")
        .append("Content-Length: ")
        .append(contentLength)
        .append("\r\n")
        .append("Cache-Control: no-store\r\n")
        .append("Connection: close\r\n")
    if (isPartial) {
      headerSb.append("Content-Range: bytes $start-$end/$totalLength\r\n")
    }
    headerSb.append("\r\n")
    output.write(headerSb.toString().toByteArray())

    if (!headOnly && contentLength > 0L) {
      RandomAccessFile(file, "r").use { raf ->
        raf.seek(start)
        val buffer = ByteArray(16 * 1024)
        var remaining = contentLength
        while (remaining > 0L) {
          val toRead = minOf(buffer.size.toLong(), remaining).toInt()
          val read = raf.read(buffer, 0, toRead)
          if (read <= 0) break
          output.write(buffer, 0, read)
          remaining -= read
        }
      }
    }
    output.flush()
  }

  private fun parseByteRange(rangeHeader: String?, totalLength: Long): Pair<Long, Long>? {
    if (rangeHeader.isNullOrBlank() || totalLength <= 0L) return null
    val spec = rangeHeader.trim().removePrefix("bytes=").substringBefore(",").trim()
    val dash = spec.indexOf('-')
    if (dash == -1) return null
    val startStr = spec.substring(0, dash).trim()
    val endStr = spec.substring(dash + 1).trim()
    val start: Long
    val end: Long
    if (startStr.isEmpty()) {
      val suffix = endStr.toLongOrNull() ?: return null
      if (suffix <= 0L) return null
      start = (totalLength - suffix).coerceAtLeast(0L)
      end = totalLength - 1L
    } else {
      start = startStr.toLongOrNull() ?: return null
      end = if (endStr.isEmpty()) totalLength - 1L else (endStr.toLongOrNull() ?: return null)
    }
    if (start < 0L || start >= totalLength || end < start) return null
    return start to minOf(end, totalLength - 1L)
  }

  private fun readHttpHead(input: BufferedInputStream): String? {
    val bytes = ByteArray(8192)
    var count = 0
    while (count < bytes.size) {
      val b = input.read()
      if (b == -1) break
      bytes[count++] = b.toByte()
      if (count >= 4 &&
        bytes[count - 4] == '\r'.code.toByte() &&
        bytes[count - 3] == '\n'.code.toByte() &&
        bytes[count - 2] == '\r'.code.toByte() &&
        bytes[count - 1] == '\n'.code.toByte()
      ) {
        return String(bytes, 0, count - 4, Charsets.UTF_8)
      }
    }
    return null
  }

  private fun parseRequestHeaders(lines: List<String>): Map<String, String> {
    val map = mutableMapOf<String, String>()
    for (line in lines) {
      val idx = line.indexOf(':')
      if (idx > 0) {
        map[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
      }
    }
    return map
  }

  private fun parseQuery(query: String): Map<String, String> {
    val map = mutableMapOf<String, String>()
    if (query.isBlank()) return map
    for (pair in query.split("&")) {
      val idx = pair.indexOf('=')
      if (idx > 0) {
        val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
        val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        map[k] = v
      }
    }
    return map
  }

  private fun writeSimpleStatus(output: BufferedOutputStream, code: Int, message: String) {
    val resp = "HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    output.write(resp.toByteArray())
    output.flush()
  }

  private companion object {
    const val TAG = "LocalStreamProxyServer"
  }
}
