package app.stash.recs

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTest {
    @Test
    fun parsesLrcWithSeveralStamps() {
        val lines = Lyrics.parseLrc("[00:12.50] hello\n[01:02.00][02:00.10] chorus\nnot a line\n[00:05.00]")
        assertEquals(listOf(5_000L to "", 12_500L to "hello", 62_000L to "chorus", 120_100L to "chorus"), lines)
    }

    @Test
    fun `musixmatch token from debug info or bare`() {
        val tok = "26102673bb286cead8fd088c6bc69e04013a2bc05f400742279376"
        org.junit.Assert.assertEquals(tok, Musixmatch.parseToken("---- Debug Info ----\n[Device]: iPhone\n[UserToken]: $tok\n[Other]: x"))
        org.junit.Assert.assertEquals(tok, Musixmatch.parseToken("  $tok \n"))
        org.junit.Assert.assertNull(Musixmatch.parseToken("hello"))
    }

    @Test
    fun `richsync words get absolute start times`() {
        val body = """[{"ts":1.38,"te":1.56,"l":[{"c":"hello","o":0},{"c":" ","o":0.03},{"c":"world","o":0.05}],"x":"hello world"}]"""
        val lines = Musixmatch.parseRichsync(body)
        org.junit.Assert.assertEquals(1, lines.size)
        org.junit.Assert.assertEquals(1380L, lines[0].startMs)
        org.junit.Assert.assertEquals("hello world", lines[0].text)
        org.junit.Assert.assertEquals(listOf(1380L to "hello", 1410L to " ", 1430L to "world"), lines[0].words)
    }

    @Test
    fun `chrome translator entities decode`() {
        org.junit.Assert.assertEquals("it's \"me\" & you", Lyrics.unescapeHtml("it&#39;s &quot;me&quot; &amp; you"))
    }

    @Test
    fun `android debug info uses the android app id`() {
        val info = "[App]: com.musixmatch.android.lyrify\n[Model]: Pixel 3a\n[OSVersion]: 35\n[UserToken]:\n261026dbcc4d19c2833c07d92886a911bb11fd57987f7804667849"
        org.junit.Assert.assertEquals("android-player-v1.0", Musixmatch.appIdFor(info))
        org.junit.Assert.assertEquals("261026dbcc4d19c2833c07d92886a911bb11fd57987f7804667849", Musixmatch.parseToken(info))
        org.junit.Assert.assertEquals("mac-ios-v2.0", Musixmatch.appIdFor("[Device]: iPhone\n[UserToken]: abc"))
    }
}
