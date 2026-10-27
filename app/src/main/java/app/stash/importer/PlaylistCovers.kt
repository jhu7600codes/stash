package app.stash.importer

import android.content.Context
import app.stash.data.db.PlaylistDao
import app.stash.providers.BROWSER_UA
import app.stash.providers.getText
import app.stash.providers.httpIo
import app.stash.providers.lenientJson
import app.stash.providers.str
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * real playlist covers (custom uploaded ones included) for playlists imported from exportify
 * files, which only carry the playlist's name. the spotify ids come from your profile page or
 * from exportify's own playlist list; spotify's oembed then gives each one's name and cover,
 * and they're matched to stash playlists by exact name (the way exportify names its files).
 * found ids are remembered, so later exportify imports get their cover by themselves.
 */
@Singleton
class PlaylistCovers @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
    private val playlists: PlaylistDao,
) {
    data class Result(val found: Int, val matched: Int)

    private val known get() = context.getSharedPreferences("spotify_playlists", Context.MODE_PRIVATE)

    /** a spotify profile link -> its public playlists. the page only lists the first ~10 */
    suspend fun fromProfile(link: String): Result {
        val user = Regex("""(?:open\.spotify\.com/user/|spotify:user:)([^/?\s#]+)""").find(link)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("That doesn't look like a Spotify profile link")
        val html = httpIo { http.getText("https://open.spotify.com/user/$user", mapOf("User-Agent" to BROWSER_UA)) }
        val ids = Regex("""playlist/([A-Za-z0-9]{22})""").findAll(html).map { it.groupValues[1] }.distinct().toList()
        return apply(ids.associateWith { null })
    }

    /** playlist ids (with names, when known) -> covers on the matching stash playlists */
    suspend fun apply(found: Map<String, String?>): Result = coroutineScope {
        val gate = Semaphore(4)
        val info = found.keys.map { id ->
            async { gate.withPermit { id to runCatching { oembed(id) }.getOrNull() } }
        }.awaitAll().mapNotNull { (id, o) -> o?.let { Triple(id, it.first ?: found[id], it.second) } }

        val edit = known.edit()
        info.forEach { (id, name, _) -> if (name != null) edit.putString(key(name), id) }
        edit.apply()

        var matched = 0
        val byKey = info.filter { it.second != null }.associateBy { key(it.second!!) }
        for (p in playlists.all()) {
            if (p.origin != "spotify") continue
            val hit = (p.originId?.let { id -> info.firstOrNull { it.first == id } }) ?: byKey[key(p.name)] ?: continue
            val cover = hit.third ?: continue
            playlists.setSpotify(p.id, hit.second ?: p.name, cover, hit.first)
            matched++
        }
        Result(info.size, matched)
    }

    /** for the importer: a spotify id seen before for a playlist of this name, if any */
    fun knownId(name: String): String? = known.getString(key(name), null)

    /** spotify id -> (name, cover) */
    suspend fun oembed(id: String): Pair<String?, String?> {
        val url = "https://open.spotify.com/oembed".toHttpUrl().newBuilder()
            .addQueryParameter("url", "https://open.spotify.com/playlist/$id").build()
        val o = lenientJson.parseToJsonElement(httpIo { http.getText(url.toString()) }).jsonObject
        return o.str("title") to o.str("thumbnail_url")
    }

    companion object {
        /**
         * exportify names files like "my_playlist_(2024).csv" (punctuation and spaces become "_",
         * lowercased) and stash turns that back into "my playlist (2024)", so compare letters
         * and digits only
         */
        fun key(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }
    }
}
