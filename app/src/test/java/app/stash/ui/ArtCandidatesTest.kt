package app.stash.ui

import app.stash.ui.components.artCandidates
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtCandidatesTest {
    @Test
    fun spotifyCoversGetMirrors() {
        val c = artCandidates("https://i.scdn.co/image/ab67616d0000b273e8b066f70c206551210d902b")
        assertEquals(3, c.size) // itself + the two other hosts
        assertEquals("https://image-cdn-ak.spotifycdn.com/image/ab67616d0000b273e8b066f70c206551210d902b", c[1])
    }

    @Test
    fun otherCoversStayAsIs() {
        assertEquals(listOf("https://i.ytimg.com/vi/x/hqdefault.jpg"), artCandidates("https://i.ytimg.com/vi/x/hqdefault.jpg"))
        assertEquals(emptyList<Any>(), artCandidates(null))
    }
}
