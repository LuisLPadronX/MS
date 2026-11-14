package com.example.data.cache

import android.content.Context
import com.example.data.model.MusicTrack
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class CachedTrackEntry(
  val track: MusicTrack,
  val playCount: Int,
  val fileSizeBytes: Long,
  val cachedAtEpochMs: Long,
  val isAvailableOffline: Boolean,
)

/**
 * Automatic playback cache:
 * - Tracks how many times each song is actually played.
 * - Never writes audio bytes on the 1st or 2nd play.
 * - Automatically caches audio into app cacheDir once playCount >= AUTO_CACHE_THRESHOLD (3),
 *   allowing offline playback of frequently played tracks within a strict LRU disk budget.
 */
class AudioCacheManager(
  context: Context,
  private val client: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(25, TimeUnit.SECONDS)
      .followRedirects(true)
      .followSslRedirects(true)
      .build(),
) {
  private val cacheRoot = File(context.applicationContext.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
  private val indexFile = File(cacheRoot, INDEX_FILE_NAME)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutex = Mutex()

  private val records = linkedMapOf<String, CacheRecord>()
  private val activeDownloads = mutableSetOf<String>()

  private val _cachedTracks = MutableStateFlow<List<CachedTrackEntry>>(emptyList())
  val cachedTracks: StateFlow<List<CachedTrackEntry>> = _cachedTracks.asStateFlow()

  init {
    loadIndexFromDiskSync()
    publishSnapshot()
  }

  fun getOfflineFile(videoId: String): File? {
    val file = audioFileFor(videoId)
    return if (file.exists() && file.length() > MIN_VALID_AUDIO_BYTES) file else null
  }

  fun getPlayCount(videoId: String): Int = synchronized(records) { records[videoId]?.playCount ?: 0 }

  fun recordPlaybackAndCacheIfEligible(
    track: MusicTrack,
    streamUrl: String?,
    headers: Map<String, String>,
    isHls: Boolean,
  ) {
    scope.launch {
      recordPlaybackAndProcess(track, streamUrl, headers, isHls)
    }
  }

  internal suspend fun recordPlaybackAndProcess(
    track: MusicTrack,
    streamUrl: String?,
    headers: Map<String, String>,
    isHls: Boolean,
  ) {
    val shouldStartDownload: Boolean
    mutex.withLock {
      val nextCount: Int
      synchronized(records) {
        val existing = records[track.videoId]
        nextCount = (existing?.playCount ?: 0) + 1
        val now = System.currentTimeMillis()
        val updated =
          CacheRecord(
            track = track,
            playCount = nextCount,
            lastPlayedMs = now,
            cachedAtMs = existing?.cachedAtMs ?: 0L,
            fileSizeBytes = existing?.fileSizeBytes ?: 0L,
          )
        records[track.videoId] = updated
      }
      saveIndexLocked()

      val alreadyCached = getOfflineFile(track.videoId) != null
      shouldStartDownload =
        nextCount >= AUTO_CACHE_THRESHOLD &&
          !alreadyCached &&
          !isHls &&
          !streamUrl.isNullOrBlank() &&
          synchronized(activeDownloads) { activeDownloads.add(track.videoId) }
    }
    publishSnapshot()

    if (shouldStartDownload && !streamUrl.isNullOrBlank()) {
      try {
        downloadToCache(track, streamUrl, headers)
      } finally {
        mutex.withLock {
          synchronized(activeDownloads) { activeDownloads.remove(track.videoId) }
        }
      }
    }
  }

  fun removeCachedTrack(videoId: String) {
    scope.launch {
      mutex.withLock {
        audioFileFor(videoId).delete()
        synchronized(records) {
          records[videoId]?.let { rec ->
            records[videoId] = rec.copy(cachedAtMs = 0L, fileSizeBytes = 0L)
          }
        }
        saveIndexLocked()
      }
      publishSnapshot()
    }
  }

  fun clearAllCache() {
    scope.launch { clearAllCacheNow() }
  }

  internal suspend fun clearAllCacheNow() {
    mutex.withLock {
      cacheRoot.listFiles()?.forEach { f ->
        if (f.name != INDEX_FILE_NAME) f.delete()
      }
      synchronized(records) { records.clear() }
      saveIndexLocked()
    }
    publishSnapshot()
  }

  private suspend fun downloadToCache(
    track: MusicTrack,
    url: String,
    headers: Map<String, String>,
  ) {
    val targetFile = audioFileFor(track.videoId)
    val tempFile = File(cacheRoot, "${track.videoId}.tmp")
    val success =
      runCatching {
          val reqBuilder = Request.Builder().url(url).get()
          headers.forEach { (k, v) -> reqBuilder.header(k, v) }
          client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching false
            val body = resp.body ?: return@runCatching false
            val contentLength = body.contentLength()
            if (contentLength > MAX_SINGLE_FILE_BYTES) return@runCatching false

            body.byteStream().use { input ->
              tempFile.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                  val read = input.read(buffer)
                  if (read == -1) break
                  total += read
                  if (total > MAX_SINGLE_FILE_BYTES) {
                    return@runCatching false
                  }
                  output.write(buffer, 0, read)
                }
              }
            }
            tempFile.exists() && tempFile.length() > MIN_VALID_AUDIO_BYTES
          }
        }
        .getOrDefault(false)

    if (success) {
      if (targetFile.exists()) targetFile.delete()
      if (tempFile.renameTo(targetFile)) {
        mutex.withLock {
          val existing = records[track.videoId]
          if (existing != null) {
            records[track.videoId] =
              existing.copy(
                cachedAtMs = System.currentTimeMillis(),
                fileSizeBytes = targetFile.length(),
              )
          }
          enforceLruBudgetLocked()
          saveIndexLocked()
        }
        publishSnapshot()
        return
      }
    }
    tempFile.delete()
  }

  private fun enforceLruBudgetLocked() {
    val cachedEntries =
      records.values
        .filter { getOfflineFile(it.track.videoId) != null }
        .sortedBy { it.lastPlayedMs }
        .toMutableList()

    var totalBytes = cachedEntries.sumOf { audioFileFor(it.track.videoId).length() }
    while (cachedEntries.size > MAX_CACHED_TRACKS || totalBytes > MAX_TOTAL_CACHE_BYTES) {
      val oldest = cachedEntries.removeFirstOrNull() ?: break
      val file = audioFileFor(oldest.track.videoId)
      val len = file.length()
      file.delete()
      totalBytes = (totalBytes - len).coerceAtLeast(0L)
      records[oldest.track.videoId] = oldest.copy(cachedAtMs = 0L, fileSizeBytes = 0L)
    }
  }

  private fun audioFileFor(videoId: String): File = File(cacheRoot, "$videoId.audio")

  private fun publishSnapshot() {
    val list =
      synchronized(records) {
        records.values
          .mapNotNull { rec ->
            val file = getOfflineFile(rec.track.videoId)
            if (file != null) {
              CachedTrackEntry(
                track = rec.track,
                playCount = rec.playCount,
                fileSizeBytes = file.length(),
                cachedAtEpochMs = rec.cachedAtMs,
                isAvailableOffline = true,
              )
            } else {
              null
            }
          }
          .sortedByDescending { it.cachedAtEpochMs }
      }
    _cachedTracks.value = list
  }

  private fun loadIndexFromDiskSync() {
    if (!indexFile.exists()) return
    runCatching {
      val arr = JSONArray(indexFile.readText())
      synchronized(records) {
        records.clear()
        for (i in 0 until arr.length()) {
          val o = arr.optJSONObject(i) ?: continue
          val videoId = o.optString("videoId", "")
          if (videoId.isBlank()) continue
          val track =
            MusicTrack(
              videoId = videoId,
              title = o.optString("title", "Pista"),
              artist = o.optString("artist", "YouTube"),
              durationText = o.optString("durationText", ""),
              durationSeconds = o.optLong("durationSeconds", 0L),
              thumbnailUrl = o.optString("thumbnailUrl", ""),
            )
          records[videoId] =
            CacheRecord(
              track = track,
              playCount = o.optInt("playCount", 0),
              lastPlayedMs = o.optLong("lastPlayedMs", 0L),
              cachedAtMs = o.optLong("cachedAtMs", 0L),
              fileSizeBytes = o.optLong("fileSizeBytes", 0L),
            )
        }
      }
    }
  }

  private fun saveIndexLocked() {
    runCatching {
      val arr = JSONArray()
      synchronized(records) {
        records.values.forEach { rec ->
          arr.put(
            JSONObject().apply {
              put("videoId", rec.track.videoId)
              put("title", rec.track.title)
              put("artist", rec.track.artist)
              put("durationText", rec.track.durationText)
              put("durationSeconds", rec.track.durationSeconds)
              put("thumbnailUrl", rec.track.thumbnailUrl)
              put("playCount", rec.playCount)
              put("lastPlayedMs", rec.lastPlayedMs)
              put("cachedAtMs", rec.cachedAtMs)
              put("fileSizeBytes", rec.fileSizeBytes)
            }
          )
        }
      }
      indexFile.writeText(arr.toString())
    }
  }

  private data class CacheRecord(
    val track: MusicTrack,
    val playCount: Int,
    val lastPlayedMs: Long,
    val cachedAtMs: Long,
    val fileSizeBytes: Long,
  )

  companion object {
    const val AUTO_CACHE_THRESHOLD = 3
    private const val CACHE_DIR_NAME = "sonora_audio_cache"
    private const val INDEX_FILE_NAME = "cache_index.json"
    private const val MIN_VALID_AUDIO_BYTES = 16 * 1024L
    private const val MAX_SINGLE_FILE_BYTES = 35L * 1024L * 1024L
    private const val MAX_TOTAL_CACHE_BYTES = 220L * 1024L * 1024L
    private const val MAX_CACHED_TRACKS = 25

    fun isEligibleForAutoCache(playCount: Int): Boolean = playCount >= AUTO_CACHE_THRESHOLD
  }
}
