package com.example

import com.example.data.model.AudioQuality
import com.example.data.model.ThemeMode
import com.example.data.repository.YouTubeMusicRepository
import com.example.player.YouTubeStreamExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun extractVideoId_handlesUrlsAndRawIds() {
    assertEquals(
      "jfKfPfyJRdk",
      YouTubeMusicRepository.extractVideoId("https://www.youtube.com/watch?v=jfKfPfyJRdk"),
    )
    assertEquals(
      "dQw4w9WgXcQ",
      YouTubeMusicRepository.extractVideoId("https://youtu.be/dQw4w9WgXcQ?si=abc"),
    )
    assertEquals(
      "5qap5aO4i9A",
      YouTubeMusicRepository.extractVideoId("https://music.youtube.com/watch?v=5qap5aO4i9A"),
    )
    assertEquals("jfKfPfyJRdk", YouTubeMusicRepository.extractVideoId("jfKfPfyJRdk"))
    assertNull(YouTubeMusicRepository.extractVideoId("daft punk instant crush"))
  }

  @Test
  fun parseDurationToSeconds_parsesMinutesAndHours() {
    assertEquals(225L, YouTubeMusicRepository.parseDurationToSeconds("3:45"))
    assertEquals(3723L, YouTubeMusicRepository.parseDurationToSeconds("1:02:03"))
    assertEquals(0L, YouTubeMusicRepository.parseDurationToSeconds(""))
  }

  @Test
  fun audioQualityAndThemeModes_haveExpectedOrdering() {
    assertTrue(AudioQuality.DATA_SAVER.maxBitrateBps < AudioQuality.BALANCED.maxBitrateBps)
    assertTrue(AudioQuality.BALANCED.maxBitrateBps < AudioQuality.HIGH.maxBitrateBps)
    assertEquals(3, ThemeMode.entries.size)
  }

  @Test
  fun parseCipherOpsAndApply_deciphersSignatureCorrectly() {
    val playerJs =
      """
      var Ab={xR:function(a){a.reverse()},yS:function(a,b){a.splice(0,b)},zW:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c}};
      Xy=function(a){a=a.split("");Ab.zW(a,3);Ab.yS(a,2);Ab.xR(a);return a.join("")};
      """
        .trimIndent()

    val extractor = YouTubeStreamExtractor(skipNetworkProbe = true)
    val ops = extractor.parseCipherOpsFromPlayerJs(playerJs)
    assertEquals(3, ops.size)
    // "abcdefgh" -> Swap(3): "dbcaefgh" -> Splice(2): "caefgh" -> Reverse: "hgfeac"
    assertEquals("hgfeac", extractor.applyCipherOps("abcdefgh", ops))
  }
}
