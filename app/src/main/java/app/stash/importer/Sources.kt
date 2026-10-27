package app.stash.importer

import app.stash.data.prefs.Settings
import app.stash.providers.BROWSER_UA
import app.stash.providers.ProviderException
import app.stash.providers.arr
import app.stash.providers.asObjectList
import app.stash.providers.getText
import app.stash.providers.httpIo
import app.stash.providers.lenientJson
import app.stash.providers.num
import app.stash.providers.obj
import app.stash.providers.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** a song as another service describes it, before it's matched to a provider */
data class SourceTrack(
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val artworkUrl: String?,
    /** e.g. spotify:track:… , used to fetch the real cover */
    val sourceUri: String? = null,
)

data class SourcePlaylist(
    val name: String,
    val origin: String,
    val originId: String?,
    val coverUrl: String?,
    val tracks: List<SourceTrack>,
)

/**
 * spotify without the (now premium-only) web api: a playlist or album link is read from
 * spotify's public embed page, which ships the whole tracklist as json for its own player.
 */
@Singleton
class SpotifyLink @Inject constructor(private val http: OkHttpClient) {
    private val idPattern = Regex("""(?:open\.spotify\.com/(?:intl-[a-z]+/)?|spotify:)(playlist|album)[/:]([A-Za-z0-9]+)""")

    fun findLink(text: String): String? =
        Regex("""https?://(?:open\.spotify\.com|spotify\.link)/\S+""").find(text)?.value

    suspend fun playlist(link: String): SourcePlaylist {
        val (kind, id) = idPattern.find(expandShortLink(link))?.destructured
            ?: throw ProviderException("That doesn't look like a Spotify playlist or album link")
        val html = httpIo { http.getText("https://open.spotify.com/embed/$kind/$id", mapOf("User-Agent" to BROWSER_UA)) }
        val json = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: throw ProviderException("Couldn't read that Spotify page")
        val entity = lenientJson.parseToJsonElement(json).jsonObject
            .obj("props")?.obj("pageProps")?.obj("state")?.obj("data")?.obj("entity")
            ?: throw ProviderException("That Spotify page had no playlist in it (is it private?)")
        val name = entity.str("name") ?: entity.str("title") ?: "Spotify $kind"
        val cover = entity.obj("coverArt")?.get("sources").asObjectList().lastOrNull()?.str("url")
        val tracks = entity["trackList"].asObjectList().mapNotNull { t ->
            SourceTrack(
                title = t.str("title") ?: return@mapNotNull null,
                // artists come as "a,\u00a0b"
                artist = t.str("subtitle")?.replace('\u00a0', ' ') ?: "",
                album = if (kind == "album") name else null,
                durationMs = (t.num("duration") ?: 0.0).toLong(),
                artworkUrl = if (kind == "album") cover else null,
                sourceUri = t.str("uri"),
            )
        }
        if (tracks.isEmpty()) throw ProviderException("No songs found in that $kind")
        return SourcePlaylist(name, "spotify", id, cover, tracks)
    }

    /**
     * how many songs a playlist really has, from the normal page's meta tags, since the embed
     * stops at 100. null when it can't tell (albums, network trouble).
     */
    suspend fun songCount(link: String): Int? {
        val (kind, id) = idPattern.find(expandShortLink(link))?.destructured ?: return null
        if (kind != "playlist") return null
        val html = runCatching {
            httpIo { http.getText("https://open.spotify.com/playlist/$id", mapOf("User-Agent" to BROWSER_UA)) }
        }.getOrNull() ?: return null
        return Regex("""<meta name="music:song_count" content="(\d+)"""").find(html)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** spotify.link/xyz share links redirect to the real open.spotify.com url */
    private suspend fun expandShortLink(link: String): String {
        if (!link.contains("spotify.link")) return link
        return httpIo {
            http.newCall(Request.Builder().url(link).header("User-Agent", BROWSER_UA).build()).execute().use {
                it.request.url.toString() + " " + it.body?.string()?.take(4000).orEmpty()
            }
        }
    }
}

/**
 * csv files from exportify.net (one per playlist, or the "export all" zip). covers private
 * playlists and liked songs, since exportify does the spotify login on its side.
 */
object ExportifyCsv {
    fun read(fileName: String, input: InputStream): List<SourcePlaylist> =
        if (fileName.endsWith(".zip", ignoreCase = true)) {
            val out = mutableListOf<SourcePlaylist>()
            ZipInputStream(input).use { zip ->
                generateSequence { zip.nextEntry }.forEach { e ->
                    if (!e.isDirectory && e.name.endsWith(".csv", ignoreCase = true)) {
                        // read the entry without closing the zip stream
                        val text = zip.readBytes().toString(Charsets.UTF_8)
                        parse(e.name.substringAfterLast('/'), text)?.let(out::add)
                    }
                }
            }
            out
        } else {
            listOfNotNull(parse(fileName, input.readBytes().toString(Charsets.UTF_8)))
        }

    fun parse(fileName: String, text: String): SourcePlaylist? {
        val rows = csvRows(text.removePrefix("\uFEFF"))
        if (rows.size < 2) return null
        val header = rows.first().map { it.trim().lowercase() }
        fun col(vararg names: String) = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } }
        val title = col("track name") ?: return null
        val artist = col("artist name(s)", "artist name", "artists")
        val album = col("album name")
        val duration = col("duration (ms)", "track duration (ms)")
        val image = col("album image url")
        val uri = col("track uri", "spotify id")
        val tracks = rows.drop(1).mapNotNull { r ->
            val t = r.getOrNull(title)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SourceTrack(
                title = t,
                // newer exportify joins artists with ';', older versions with ','
                artist = artist?.let { r.getOrNull(it) }.orEmpty().split(';').joinToString(", ") { it.trim() },
                album = album?.let { r.getOrNull(it) }?.takeIf { it.isNotBlank() },
                durationMs = duration?.let { r.getOrNull(it)?.toLongOrNull() } ?: 0,
                artworkUrl = image?.let { r.getOrNull(it) }?.takeIf { it.startsWith("http") },
                sourceUri = uri?.let { r.getOrNull(it) }?.takeIf { it.isNotBlank() }
                    ?.let { if (it.startsWith("spotify:")) it else "spotify:track:$it" },
            )
        }
        if (tracks.isEmpty()) return null
        val name = fileName.substringBeforeLast('.').trimEnd('_').replace('_', ' ')
        return SourcePlaylist(name, "spotify", null, null, tracks)
    }

    /** minimal rfc 4180: quoted fields, doubled quotes, newlines inside quotes */
    internal fun csvRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row.add(field.toString()); field.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row.add(field.toString()); field.clear()
                    if (row.any { it.isNotEmpty() }) rows.add(row)
                    row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        row.add(field.toString())
        if (row.any { it.isNotEmpty() }) rows.add(row)
        return rows
    }
}

/**
 * apple music: with a musickit developer token the catalog api is used, otherwise the public
 * playlist page is read (it embeds the whole tracklist as json for its own rendering).
 */
@Singleton
class AppleMusic @Inject constructor(
    private val http: OkHttpClient,
    private val settings: Settings,
) {
    private val linkPattern = Regex("""music\.apple\.com/([a-z]{2})/playlist/[^/]*/?(pl\.[A-Za-z0-9.\-]+)""")

    fun findLink(text: String): String? =
        Regex("""https?://music\.apple\.com/\S+""").find(text)?.value

    suspend fun playlist(link: String): SourcePlaylist {
        val m = linkPattern.find(link) ?: throw ProviderException("That doesn't look like an Apple Music playlist link")
        val (storefront, id) = m.destructured
        val token = settings.current().appleDeveloperToken
        return if (token.isNotBlank()) {
            runCatching { viaApi(storefront, id, token) }.getOrElse { viaPage(link, id) }
        } else viaPage(link, id)
    }

    private suspend fun viaApi(storefront: String, id: String, token: String): SourcePlaylist {
        val headers = mapOf("Authorization" to "Bearer $token", "Origin" to "https://music.apple.com")
        val root = lenientJson.parseToJsonElement(
            httpIo { http.getText("https://api.music.apple.com/v1/catalog/$storefront/playlists/$id", headers) }
        ).jsonObject
        val pl = root["data"].asObjectList().firstOrNull() ?: throw ProviderException("Playlist not found")
        val attrs = pl.obj("attributes")
        val tracks = mutableListOf<SourceTrack>()
        var next: String? = "/v1/catalog/$storefront/playlists/$id/tracks?limit=100"
        while (next != null) {
            val page = lenientJson.parseToJsonElement(httpIo { http.getText("https://api.music.apple.com$next", headers) }).jsonObject
            page["data"].asObjectList().forEach { t ->
                val a = t.obj("attributes") ?: return@forEach
                tracks += SourceTrack(
                    title = a.str("name") ?: return@forEach,
                    artist = a.str("artistName") ?: "",
                    album = a.str("albumName"),
                    durationMs = (a.num("durationInMillis") ?: 0.0).toLong(),
                    artworkUrl = a.obj("artwork")?.str("url")?.let(::artwork),
                )
            }
            next = page.str("next")
        }
        return SourcePlaylist(attrs?.str("name") ?: "Apple Music playlist", "apple", id,
            attrs?.obj("artwork")?.str("url")?.let(::artwork), tracks)
    }

    private suspend fun viaPage(link: String, id: String): SourcePlaylist {
        val html = httpIo { http.getText(link, mapOf("User-Agent" to BROWSER_UA)) }
        val json = Regex("""<script[^>]*id="serialized-server-data"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: throw ProviderException("Couldn't read that playlist page")
        val tracks = mutableListOf<SourceTrack>()
        collect(lenientJson.parseToJsonElement(json), tracks)
        if (tracks.isEmpty()) throw ProviderException("No songs found on that page")
        val title = Regex("""<meta property="og:title" content="([^"]*)"""").find(html)?.groupValues?.get(1)
            ?.let { android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString() }
            ?.substringBefore(" - Apple Music")?.substringBefore(" on Apple Music")
        val cover = Regex("""<meta property="og:image" content="([^"]*)"""").find(html)?.groupValues?.get(1)
        return SourcePlaylist(title ?: "Apple Music playlist", "apple", id, cover, tracks.distinct())
    }

    /** every object with a title + artistName in the page data is a track lockup */
    private fun collect(el: JsonElement, out: MutableList<SourceTrack>) {
        when (el) {
            is JsonObject -> {
                val title = el.str("title")
                val artist = el.str("artistName")
                if (title != null && artist != null && el.containsKey("duration")) {
                    out += SourceTrack(
                        title = title,
                        artist = artist,
                        album = el.arr("tertiaryLinks")?.firstOrNull()?.let { (it as? JsonObject)?.str("title") }
                            ?.removeSuffix(" - Single")?.removeSuffix(" - EP"),
                        durationMs = (el.num("duration") ?: 0.0).toLong(),
                        artworkUrl = el.obj("artwork")?.obj("dictionary")?.str("url")?.let(::artwork),
                    )
                    return
                }
                el.values.forEach { collect(it, out) }
            }
            is kotlinx.serialization.json.JsonArray -> el.forEach { collect(it, out) }
            else -> Unit
        }
    }

    private fun artwork(template: String) =
        template.replace("{w}", "600").replace("{h}", "600").replace("{f}", "jpg").replace("{c}", "bb")
}
