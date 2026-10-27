package app.stash.providers

import android.text.Html
import app.stash.data.db.Track
import app.stash.data.prefs.Settings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** jamendo: official api, cc-licensed music. needs a free client id from devportal.jamendo.com */
@Singleton
class JamendoProvider @Inject constructor(
    private val http: OkHttpClient,
    private val settings: Settings,
) : MusicProvider {
    override val id = "jamendo"
    override val displayName = "Jamendo"

    override suspend fun isConfigured() = settings.current().jamendoClientId.isNotBlank()

    private suspend fun api(path: String, params: Map<String, String>): List<JsonObject> {
        val clientId = settings.current().jamendoClientId
        if (clientId.isBlank()) throw ProviderException("Jamendo needs a client ID (Settings)")
        val url = "https://api.jamendo.com/v3.0/$path/".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("format", "json")
            .addQueryParameter("audioformat", "mp32")
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        val text = httpIo { http.getText(url.toString()) }
        val root = lenientJson.parseToJsonElement(text).jsonObject
        val status = root.obj("headers")?.str("status")
        if (status != null && status != "success") {
            throw ProviderException("Jamendo: ${root.obj("headers")?.str("error_message") ?: status}")
        }
        return root["results"].asObjectList()
    }

    private fun toTrack(o: JsonObject): Track? {
        val trackId = o.str("id") ?: return null
        return Track(
            id = Track.makeId(id, trackId),
            title = o.str("name") ?: return null,
            artist = o.str("artist_name") ?: "",
            album = o.str("album_name"),
            durationMs = ((o.num("duration") ?: 0.0) * 1000).toLong(),
            artworkUrl = o.str("album_image") ?: o.str("image"),
            provider = id,
            sourceId = trackId,
            streamHint = o.str("audio"),
        )
    }

    override suspend fun search(query: String, limit: Int) =
        api("tracks", mapOf("search" to query, "limit" to "$limit", "include" to "musicinfo")).mapNotNull(::toTrack)

    override suspend fun metadata(sourceId: String) =
        api("tracks", mapOf("id" to sourceId)).firstOrNull()?.let(::toTrack)

    override suspend fun resolveStream(locator: Locator): StreamSource {
        val url = locator.streamHint ?: metadata(locator.sourceId)?.streamHint
            ?: throw ProviderException("Jamendo: song not found")
        return StreamSource(url)
    }

    override suspend fun downloadSource(locator: Locator): DownloadSource {
        val o = api("tracks", mapOf("id" to locator.sourceId)).firstOrNull()
        val allowed = o?.str("audiodownload_allowed") != "false"
        val url = o?.str("audiodownload")?.takeIf { allowed } ?: locator.streamHint ?: o?.str("audio")
            ?: throw ProviderException("Jamendo: no download for this one")
        return DownloadSource.Http(url, "mp3")
    }
}

/**
 * internet archive. search finds items (usually whole albums or collections), then each item's
 * metadata lists its audio files, which become tracks. sourceId = "identifier/filename".
 */
@Singleton
class InternetArchiveProvider @Inject constructor(private val http: OkHttpClient) : MusicProvider {
    override val id = "archive"
    override val displayName = "Internet Archive"

    private fun fileUrl(identifier: String, name: String) =
        "https://archive.org/download/".toHttpUrl().newBuilder()
            .addPathSegment(identifier)
            .addPathSegments(name)
            .build().toString()

    override suspend fun search(query: String, limit: Int): List<Track> = coroutineScope {
        val url = "https://archive.org/advancedsearch.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", "($query) AND mediatype:(audio) AND NOT collection:(podcasts OR radioprograms)")
            .addQueryParameter("fl[]", "identifier")
            .addQueryParameter("sort[]", "downloads desc")
            .addQueryParameter("rows", "6")
            .addQueryParameter("output", "json")
            .build()
        val text = httpIo { http.getText(url.toString()) }
        val ids = lenientJson.parseToJsonElement(text).jsonObject.obj("response")?.get("docs").asObjectList()
            .mapNotNull { it.str("identifier") }
        val words = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
        ids.map { ident -> async { runCatching { itemTracks(ident) }.getOrDefault(emptyList()) } }
            .awaitAll()
            .flatten()
            // items can hold dozens of files, put the ones that actually mention the query first
            .sortedByDescending { t -> words.count { w -> "${t.title} ${t.artist}".lowercase().contains(w) } }
            .take(limit)
    }

    private suspend fun itemTracks(identifier: String, onlyFile: String? = null): List<Track> {
        val text = httpIo { http.getText("https://archive.org/metadata/$identifier") }
        val root = lenientJson.parseToJsonElement(text).jsonObject
        val meta = root.obj("metadata")
        val itemTitle = meta?.str("title")
        // creator is a string or, for multi-artist items, a list
        val itemCreator = meta?.str("creator") ?: meta?.arr("creator")?.firstOrNull()?.jsonPrimitive?.content
        val art = "https://archive.org/services/img/$identifier"
        return root["files"].asObjectList()
            .filter { f -> f.str("format")?.contains("MP3") == true && (onlyFile == null || f.str("name") == onlyFile) }
            .mapNotNull { f ->
                val name = f.str("name") ?: return@mapNotNull null
                val secs = parseLength(f.str("length"))
                // skip hour-long radio shows and 2 second jingles
                if (onlyFile == null && (secs > 30 * 60 || (secs in 0.1..20.0))) return@mapNotNull null
                val sourceId = "$identifier/$name"
                Track(
                    id = Track.makeId(id, sourceId),
                    title = f.str("title") ?: name.substringBeforeLast('.').replace('_', ' '),
                    artist = f.str("creator") ?: f.str("artist") ?: itemCreator ?: "unknown artist",
                    album = f.str("album") ?: itemTitle,
                    durationMs = (secs * 1000).toLong(),
                    artworkUrl = art,
                    provider = id,
                    sourceId = sourceId,
                    streamHint = fileUrl(identifier, name),
                )
            }
            .take(if (onlyFile != null) 1 else 12)
    }

    private fun parseLength(s: String?): Double {
        if (s.isNullOrBlank()) return 0.0
        if (':' in s) return s.split(':').fold(0.0) { acc, p -> acc * 60 + (p.toDoubleOrNull() ?: 0.0) }
        return s.toDoubleOrNull() ?: 0.0
    }

    override suspend fun metadata(sourceId: String): Track? {
        val ident = sourceId.substringBefore('/')
        val file = sourceId.substringAfter('/')
        return itemTracks(ident, file).firstOrNull()
    }

    override suspend fun resolveStream(locator: Locator) = StreamSource(
        locator.streamHint ?: fileUrl(locator.sourceId.substringBefore('/'), locator.sourceId.substringAfter('/'))
    )

    override suspend fun downloadSource(locator: Locator) =
        DownloadSource.Http(resolveStream(locator).url, locator.sourceId.substringAfterLast('.', "mp3"))
}

/**
 * free music archive. its old api is gone, but the search page embeds each track's info as json
 * in a data-track-info attribute, so that gets scraped. sourceId = the track page url.
 */
@Singleton
class FreeMusicArchiveProvider @Inject constructor(private val http: OkHttpClient) : MusicProvider {
    override val id = "fma"
    override val displayName = "Free Music Archive"

    private val playItem = Regex("""<div class="play-item[^"]*"[^>]*data-track-info='([^']*)'""")
    private val duration = Regex("""^\s*(\d{1,2}):(\d{2})(?::(\d{2}))?\s*$""", RegexOption.MULTILINE)
    private val albumLink = Regex("""ptxt-album[^"]*"[^>]*>\s*<a[^>]*>\s*([^<]+?)\s*</a>""")

    override suspend fun search(query: String, limit: Int): List<Track> {
        val url = "https://freemusicarchive.org/search".toHttpUrl().newBuilder()
            .addQueryParameter("quicksearch", query)
            .addQueryParameter("pageSize", "$limit")
            .build()
        val html = httpIo { http.getText(url.toString()) }
        return parse(html).take(limit)
    }

    private fun parse(html: String): List<Track> {
        val matches = playItem.findAll(html).toList()
        return matches.mapIndexedNotNull { i, m ->
            val json = Html.fromHtml(m.groupValues[1], Html.FROM_HTML_MODE_LEGACY).toString()
            val o = runCatching { lenientJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return@mapIndexedNotNull null
            // the rest of this item's markup, up to the next item
            val end = matches.getOrNull(i + 1)?.range?.first ?: minOf(html.length, m.range.last + 6000)
            val block = html.substring(m.range.last, end)
            toTrack(o, block)
        }
    }

    private fun toTrack(o: JsonObject, block: String): Track? {
        val page = o.str("url") ?: return null
        val secs = duration.find(block)?.let { d ->
            val (a, b, c) = d.destructured
            if (c.isNotEmpty()) a.toLong() * 3600 + b.toLong() * 60 + c.toLong() else a.toLong() * 60 + b.toLong()
        } ?: 0
        val album = albumLink.find(block)?.groupValues?.get(1)?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }
        return Track(
            id = Track.makeId(id, page),
            title = o.str("title") ?: return null,
            artist = o.str("artistName") ?: "",
            album = album,
            durationMs = secs * 1000,
            artworkUrl = null,
            provider = id,
            sourceId = page,
            streamHint = o.str("playbackUrl"),
        )
    }

    override suspend fun metadata(sourceId: String): Track? {
        val html = runCatching { httpIo { http.getText(sourceId) } }.getOrNull() ?: return null
        return parse(html).firstOrNull { it.sourceId == sourceId } ?: parse(html).firstOrNull()
    }

    override suspend fun resolveStream(locator: Locator): StreamSource {
        val url = locator.streamHint ?: metadata(locator.sourceId)?.streamHint
            ?: throw ProviderException("Free Music Archive: song not found")
        return StreamSource(url, mapOf("User-Agent" to BROWSER_UA))
    }

    override suspend fun downloadSource(locator: Locator): DownloadSource {
        val stream = resolveStream(locator).url
        // /stream/ and /download/ both redirect to the same mp3; download is the "official" one
        return DownloadSource.Http(stream.replace("/stream/", "/download/"), "mp3", mapOf("User-Agent" to BROWSER_UA))
    }
}
