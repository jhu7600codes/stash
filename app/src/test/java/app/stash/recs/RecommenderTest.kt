package app.stash.recs

import app.stash.data.db.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class RecommenderTest {
    private fun t(title: String, artist: String) = Track(id = title, title = title, artist = artist, provider = "youtube", sourceId = title)

    @Test
    fun interleavesKnownAndNew() {
        assertEquals(listOf(1, 10, 2, 20, 3), Recommender.interleave(listOf(1, 2, 3), listOf(10, 20)))
    }

    @Test
    fun sameSongSameKeyAcrossSources() {
        assertEquals(
            Recommender.key(t("Creep", "Radiohead")),
            Recommender.key(t("Creep (Remastered)", "Radiohead, Someone")),
        )
    }

    @Test
    fun firstArtistOnly() {
        assertEquals("Overtonight", Recommender.firstArtist("Overtonight, Somebody & Else"))
    }
}
