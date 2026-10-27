package app.stash.recs

import app.stash.data.db.ArtistGenre
import app.stash.data.db.ImportDao
import app.stash.providers.asObjectList
import app.stash.providers.getText
import app.stash.providers.httpIo
import app.stash.providers.lenientJson
import app.stash.providers.num
import app.stash.providers.str
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * an artist's main genre from deezer's public api (no key needed): find the artist, then take the
 * genre most of their albums are filed under. cached in the db, misses retried after a month.
 */
@Singleton
class Genres @Inject constructor(
    private val http: OkHttpClient,
    private val dao: ImportDao,
) {
    suspend fun of(artist: String): String? {
        val name = artist.trim()
        if (name.isEmpty()) return null
        dao.genre(name)?.let { cached ->
            if (cached.genre != null || System.currentTimeMillis() - cached.checkedAt < RETRY_MS) return cached.genre
        }
        val genre = runCatching { lookup(name) }.getOrElse { return null } // network trouble: try again next time
        dao.saveGenre(ArtistGenre(name, genre))
        return genre
    }

    /** the artist's photo from deezer (1000px), only when deezer has that exact name */
    suspend fun picture(artist: String): String? = runCatching {
        val search = "https://api.deezer.com/search/artist".toHttpUrl().newBuilder()
            .addQueryParameter("q", artist).addQueryParameter("limit", "3").build()
        lenientJson.parseToJsonElement(httpIo { http.getText(search.toString()) }).jsonObject["data"].asObjectList()
            .firstOrNull { it.str("name").orEmpty().equals(artist.trim(), ignoreCase = true) }
            ?.let { it.str("picture_xl") ?: it.str("picture_big") }
            ?.takeUnless { it.contains("/artist//") } // deezer's blank placeholder
    }.getOrNull()

    private suspend fun lookup(artist: String): String? {
        val search = "https://api.deezer.com/search/artist".toHttpUrl().newBuilder()
            .addQueryParameter("q", artist).addQueryParameter("limit", "1").build()
        val hit = lenientJson.parseToJsonElement(httpIo { http.getText(search.toString()) }).jsonObject["data"]
            .asObjectList().firstOrNull() ?: return null
        // only trust it when deezer found the same artist, not someone with a similar name
        if (!hit.str("name").orEmpty().equals(artist, ignoreCase = true)) return null
        val id = hit.num("id")?.toLong() ?: return null
        val albums = lenientJson.parseToJsonElement(httpIo { http.getText("https://api.deezer.com/artist/$id/albums?limit=15") })
            .jsonObject["data"].asObjectList()
        val genreId = albums.mapNotNull { it.num("genre_id")?.toInt() }.filter { it > 0 }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return null
        return NAMES[genreId]
    }

    companion object {
        private const val RETRY_MS = 30L * 24 * 60 * 60 * 1000

        /** deezer's genre ids (api.deezer.com/genre) */
        private val NAMES = mapOf(
            132 to "Pop", 116 to "Hip-Hop", 122 to "Reggaeton", 152 to "Rock", 113 to "Dance", 165 to "R&B",
            85 to "Alternative", 186 to "Christian", 106 to "Electronic", 466 to "Folk", 144 to "Reggae", 129 to "Jazz",
            84 to "Country", 67 to "Salsa", 65 to "Mexicano", 98 to "Classical", 173 to "Soundtracks", 464 to "Metal",
            169 to "Soul & Funk", 2 to "African", 16 to "Asian", 153 to "Blues", 75 to "Brazilian", 71 to "Cumbia",
            81 to "Indian", 95 to "Kids", 197 to "Latin",
        )
    }
}
