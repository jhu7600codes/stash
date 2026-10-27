package app.stash.providers

import app.stash.data.db.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchRankingTest {
    private fun hit(title: String, artist: String, plays: Long?, vararg tags: String, provider: String = "soundcloud") =
        SearchHit(Track(id = "$provider:$title", title = title, artist = artist, provider = provider, sourceId = title), plays, tags.toList())

    @Test
    fun parsesTags() {
        assertEquals(SearchQuery("phonk", "phonk"), SearchQuery.parse("#Phonk"))
        assertEquals(SearchQuery("memphis rap", "memphis rap"), SearchQuery.parse("tag: memphis rap"))
        assertEquals(SearchQuery("overtonight", null), SearchQuery.parse(" overtonight "))
    }

    @Test
    fun tagSearchPutsTaggedSongsFirst() {
        val results = mapOf(
            "soundcloud" to listOf(hit("Night Drive", "a", 10, "chill"), hit("Cowbell", "b", 10, "phonk")),
        )
        val merged = SearchRanking.merge(SearchQuery.parse("#phonk"), emptyList(), listOf("soundcloud"), results, underground = false)
        assertEquals("Cowbell", merged.first().track.title)
    }

    @Test
    fun undergroundDropsBigSongsAndSortsSmallFirst() {
        val results = mapOf(
            "youtube" to listOf(hit("Hit", "Star", 50_000_000, provider = "youtube")),
            "soundcloud" to listOf(hit("Demo", "Kid", 1_200, "phonk"), hit("Tape", "Kid2", 90_000, "phonk")),
        )
        val merged = SearchRanking.merge(SearchQuery.parse("#phonk"), emptyList(), listOf("youtube", "soundcloud"), results, underground = true)
        assertEquals(listOf("Demo", "Tape"), merged.map { it.track.title })
    }

    @Test
    fun sameSongFromTwoSourcesShowsOnce() {
        val results = mapOf(
            "youtube" to listOf(hit("Creep", "Radiohead", null, provider = "youtube")),
            "soundcloud" to listOf(hit("Creep", "Radiohead", 5, provider = "soundcloud")),
        )
        val merged = SearchRanking.merge(SearchQuery.parse("creep"), emptyList(), listOf("youtube", "soundcloud"), results, underground = false)
        assertEquals(1, merged.size)
        assertNull(merged.first().plays)
    }
}
