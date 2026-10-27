package app.stash.providers

import org.junit.Assert.assertEquals
import org.junit.Test

class FrontendsTest {
    @Test
    fun parsesTheCommunityList() {
        val list = Frontends.parse(
            """{"invidious": ["http://a.example:3000/", "http://b.example"], "yt2009": ["http://c.example"],
               "piped": ["http://pipedapi.x.info,http://pipedproxy.x.info", "http://1.2.3.4:8091/api,http://p.example:8080"], "ytapilegacy": []}"""
        )
        assertEquals(
            listOf(
                Frontends.Instance("invidious", "http://a.example:3000"),
                Frontends.Instance("invidious", "http://b.example"),
                Frontends.Instance("piped", "http://pipedapi.x.info"),
                Frontends.Instance("piped", "http://1.2.3.4:8091/api"),
                Frontends.Instance("yt2009", "http://c.example"),
            ),
            list,
        )
        assertEquals("1.2.3.4:8091", list[3].host)
    }

    @Test
    fun brokenListIsEmpty() {
        assertEquals(emptyList<Frontends.Instance>(), Frontends.parse("<html>"))
    }
}
