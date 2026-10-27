package app.stash.providers

import app.stash.data.db.Track
import app.stash.data.prefs.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * soundcloud's own web api (api-v2), the one soundcloud.com uses. way faster than yt-dlp's
 * scsearch and it knows tags and play counts. the client id is read from soundcloud's site
 * scripts and refreshed when it stops working.
 */
@Singleton
class SoundCloudApi @Inject constructor(
    private val http: OkHttpClient,
    private val settings: Settings,
) {
    private var clientId: String? = null
    private val idLock = Mutex()

    /**
     * the id last seen working (kept across restarts), else one read from soundcloud's site,
     * else a built-in one, so a site that won't load (vpn, region) doesn't kill soundcloud
     */
    private suspend fun clientId(refresh: Boolean = false): String = idLock.withLock {
        if (!refresh) {
            clientId?.let { return it }
            settings.soundCloudClientId()?.let { clientId = it; return it }
        }
        val scraped = runCatching { scrapeClientId() }.getOrNull()
        val id = scraped ?: FALLBACK_CLIENT_ID.takeIf { it != clientId } ?: throw ProviderException("Couldn't connect to SoundCloud")
        clientId = id
        settings.setSoundCloudClientId(id)
        id
    }

    private suspend fun scrapeClientId(): String {
        val home = httpIo { http.getText("https://soundcloud.com/discover") }
        val scripts = Regex("""https://a-v2\.sndcdn\.com/assets/[^"]+\.js""").findAll(home).map { it.value }.toList().asReversed()
        for (src in scripts) {
            val js = runCatching { httpIo { http.getText(src) } }.getOrNull() ?: continue
            Regex("""client_id\s*:\s*"([A-Za-z0-9]{32})"""").find(js)?.groupValues?.get(1)?.let { return it }
        }
        throw ProviderException("Couldn't connect to SoundCloud")
    }

    /** GET with the client id, retrying once with a fresh id if soundcloud rotated it */
    suspend fun get(path: String, params: Map<String, String> = emptyMap()): JsonObject {
        for (attempt in 0..1) {
            val id = clientId(refresh = attempt == 1)
            val url = "https://api-v2.soundcloud.com$path".toHttpUrl().newBuilder()
                .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
                .addQueryParameter("client_id", id)
                .build()
            val (code, body) = httpIo {
                http.newCall(Request.Builder().url(url).header("User-Agent", BROWSER_UA).build()).execute().use { it.code to it.body?.string().orEmpty() }
            }
            if (code == 401 || code == 403) continue
            if (code !in 200..299) throw ProviderException("SoundCloud said $code")
            return lenientJson.parseToJsonElement(body).jsonObject
        }
        throw ProviderException("SoundCloud refused the request")
    }

    companion object {
        /** read off soundcloud.com in october 2026; only used when the site itself can't be read */
        private const val FALLBACK_CLIENT_ID = "GytJkwrLebfCWmFfWOuDf7WObQo2BND9"
    }

    fun hitFrom(o: JsonObject): SearchHit? {
        // "SNIP" = a 30 second go+ preview, not worth listing
        if (o.str("policy") == "SNIP" || o.str("kind") != "track") return null
        val url = o.str("permalink_url") ?: return null
        val user = o.obj("user")
        val track = Track(
            id = Track.makeId("soundcloud", url),
            title = o.str("title") ?: return null,
            artist = user?.str("username") ?: "",
            durationMs = (o.num("full_duration") ?: o.num("duration") ?: 0.0).toLong(),
            artworkUrl = (o.str("artwork_url") ?: user?.str("avatar_url"))?.replace("-large.", "-t500x500."),
            provider = "soundcloud",
            sourceId = url,
        )
        val tags = Regex(""""([^"]+)"|(\S+)""").findAll(o.str("tag_list").orEmpty())
            .map { (it.groupValues[1].ifEmpty { it.groupValues[2] }).lowercase() }.toList() +
            listOfNotNull(o.str("genre")?.lowercase())
        return SearchHit(track, (o.num("playback_count") ?: 0.0).toLong(), tags.distinct(), user?.let(::artistFrom))
    }

    fun artistFrom(o: JsonObject): RemoteArtist? {
        val id = o.num("id")?.toLong()?.toString() ?: return null
        return RemoteArtist(
            provider = "soundcloud",
            id = id,
            name = o.str("username") ?: return null,
            avatarUrl = o.str("avatar_url")?.replace("-large.", "-t500x500."),
            followers = o.num("followers_count")?.toLong(),
        )
    }
}
