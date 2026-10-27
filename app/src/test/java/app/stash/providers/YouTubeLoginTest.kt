package app.stash.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeLoginTest {
    @Test
    fun headerString() {
        assertEquals("SID=a; SAPISID=b", YouTubeLogin.parsePasted("Cookie: SID=a; SAPISID=b"))
    }

    @Test
    fun netscapeFile() {
        val txt = "# Netscape HTTP Cookie File\n" +
            ".youtube.com\tTRUE\t/\tTRUE\t1999999999\tSAPISID\tb\n" +
            "#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t1999999999\tSID\ta\n" +
            ".google.com\tTRUE\t/\tTRUE\t1999999999\tNID\tx\n"
        assertEquals("SAPISID=b; SID=a", YouTubeLogin.parsePasted(txt))
    }

    @Test
    fun cookieEditorJson() {
        val json = """[{"domain":".youtube.com","name":"SID","value":"a","path":"/"},{"domain":".youtube.com","name":"__Secure-3PAPISID","value":"c"}]"""
        assertEquals("SID=a; __Secure-3PAPISID=c", YouTubeLogin.parsePasted(json))
    }

    @Test
    fun noSessionNoLogin() {
        assertNull(YouTubeLogin.parsePasted("PREF=f6=40000000; VISITOR_INFO1_LIVE=x"))
    }
}
