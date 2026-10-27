package app.stash.importer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExportifyCsvTest {
    private val header = "Track URI,ISRC,Track Name,Album Name,Artist Name(s),Release Date,Duration (ms),Popularity,Explicit,Added By,Added At\n"
    private val csv = "﻿" + header +
        "spotify:track:1,X,\"Do I Wanna Know?\",\"AM\",\"Arctic Monkeys\",2013,272394,80,false,,\n" +
        "spotify:track:2,Y,\"Instant Crush (feat. Julian Casablancas)\",\"Random Access Memories\",\"Daft Punk;Julian Casablancas\",2013,337560,70,false,,\n" +
        "spotify:track:3,Z,\"Say \"\"Hi\"\", ok\",\"A, B\",\"Someone\",2020,1000,1,false,,\n"

    @Test
    fun parsesExportifyCsv() {
        val p = ExportifyCsv.parse("late_night_drive.csv", csv)!!
        assertEquals("late night drive", p.name)
        assertEquals(3, p.tracks.size)
        assertEquals("Do I Wanna Know?", p.tracks[0].title)
        assertEquals("Arctic Monkeys", p.tracks[0].artist)
        assertEquals(272394L, p.tracks[0].durationMs)
        assertEquals("Daft Punk, Julian Casablancas", p.tracks[1].artist)
        assertEquals("Say \"Hi\", ok", p.tracks[2].title)
        assertEquals("A, B", p.tracks[2].album)
    }

    @Test
    fun readsExportAllZip() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                listOf("liked_songs.csv", "gym.csv").forEach { n ->
                    z.putNextEntry(ZipEntry(n)); z.write(csv.toByteArray()); z.closeEntry()
                }
            }
        }.toByteArray()
        val lists = ExportifyCsv.read("spotify_playlists.zip", bytes.inputStream())
        assertEquals(listOf("liked songs", "gym"), lists.map { it.name })
        assertEquals(3, lists[1].tracks.size)
    }
}
