package app.stash.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlaylistCoversTest {
    @Test
    fun exportifyFileNamesMatchSpotifyNames() {
        // exportify: "Late Night: Drives, 2024!" -> "late_night_drives_2024!.csv"; stash reads it back with spaces
        val fromCsv = ExportifyCsv.parse("late_night_drives_2024!.csv", "Track Name,Artist Name(s)\nsong,artist\n")!!.name
        assertEquals(PlaylistCovers.key("Late Night: Drives, 2024!"), PlaylistCovers.key(fromCsv))
        assertEquals(PlaylistCovers.key("muzix"), PlaylistCovers.key("muzix"))
        assertEquals(PlaylistCovers.key("Музыка"), PlaylistCovers.key("музыка"))
        assertNotEquals(PlaylistCovers.key("muzix"), PlaylistCovers.key("muzix 2"))
    }
}
