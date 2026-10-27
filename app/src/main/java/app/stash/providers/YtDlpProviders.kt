package app.stash.providers

import app.stash.data.db.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

internal val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

internal fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/**
 * Shared yt-dlp logic: resolving, metadata and downloads work the same for every site yt-dlp
 * supports. Subclasses only differ in how they search.
 */
abstract class YtDlpProvider(protected val ytDlp: YtDlp) : MusicProvider {

    override suspend fun resolveStream(locator: Locator): StreamSource {
        val res = ytDlp.run(
            locator.sourceId,
            listOf(
                "-f" to "bestaudio/best",
                "--no-playlist" to null,
                "--print" to "%(url)s",
                "--print" to "%(http_headers)j",
            ),
        )
        val lines = res.out.lines().filter { it.isNotBlank() }
        val url = lines.firstOrNull { it.startsWith("http") }
            ?: throw ProviderException("$displayName: no audio URL")
        val headers = lines.firstOrNull { it.startsWith("{") }?.let { line ->
            runCatching {
                lenientJson.parseToJsonElement(line).jsonObject.mapValues { it.value.jsonPrimitive.content }
            }.getOrNull()
        } ?: emptyMap()
        return StreamSource(url, headers, via = "yt-dlp")
    }

    override suspend fun metadata(sourceId: String): Track? {
        val res = runCatching {
            ytDlp.run(sourceId, listOf("-J" to null, "--no-playlist" to null))
        }.getOrNull() ?: return null
        val o = runCatching { lenientJson.parseToJsonElement(res.out).jsonObject }.getOrNull() ?: return null
        return entryToTrack(o, sourceId)
    }

    override suspend fun downloadSource(locator: Locator) = DownloadSource.YtDlp(locator.sourceId)

    /** turns a yt-dlp info dict (full or flat) into a track */
    protected open fun entryToTrack(o: JsonObject, fallbackUrl: String? = null): Track? {
        val url = o.str("webpage_url") ?: o.str("url")?.takeIf { it.startsWith("http") } ?: fallbackUrl ?: return null
        val rawTitle = o.str("track") ?: o.str("title") ?: return null
        val uploader = o.str("artist") ?: o.str("creator") ?: o.str("uploader") ?: o.str("channel") ?: ""
        val (artist, title) = splitArtistTitle(rawTitle, uploader, hasTrackField = o.str("track") != null)
        return Track(
            id = Track.makeId(id, url),
            title = title,
            artist = artist,
            album = o.str("album"),
            durationMs = ((o.num("duration") ?: 0.0) * 1000).toLong(),
            artworkUrl = artwork(o),
            provider = id,
            sourceId = url,
        )
    }

    protected open fun artwork(o: JsonObject): String? =
        o.str("thumbnail") ?: o.arr("thumbnails")?.lastOrNull()?.let { (it as? JsonObject)?.str("url") }

    protected suspend fun flatSearch(query: String): List<Track> {
        val res = ytDlp.run(query, listOf("--flat-playlist" to null, "-J" to null))
        val root = lenientJson.parseToJsonElement(res.out).jsonObject
        return root.arr("entries").orEmpty().mapNotNull { (it as? JsonObject)?.let(::entryToTrack) }
    }

    companion object {
        private val junk = Regex(
            """\s*[(\[](official\s*(music\s*)?(video|audio|lyric(s)?\s*video|visualizer)|lyrics?|audio|hd|hq|4k|mv|m/v|official)[)\]]""",
            RegexOption.IGNORE_CASE,
        )
        private val topicSuffix = Regex("""\s*-\s*Topic$""")

        /**
         * youtube titles are usually "Artist - Song (Official Video)" uploaded by some channel,
         * so pull the artist out of the title when there's a dash.
         */
        fun splitArtistTitle(rawTitle: String, uploader: String, hasTrackField: Boolean): Pair<String, String> {
            val cleanUploader = uploader.replace(topicSuffix, "").removeSuffix("VEVO").trim()
            if (hasTrackField) return cleanUploader to rawTitle
            val title = rawTitle.replace(junk, "").trim()
            val dash = Regex("""\s[-–—]\s""").find(title)
            if (dash != null) {
                val a = title.substring(0, dash.range.first).trim()
                val t = title.substring(dash.range.last + 1).trim()
                if (a.isNotEmpty() && t.isNotEmpty()) return a to t
            }
            return cleanUploader.ifEmpty { "unknown artist" } to title
        }
    }
}

@Singleton
class YouTubeProvider @Inject constructor(
    ytDlp: YtDlp,
    private val music: YouTubeMusicSearch,
    private val newPipe: NewPipeStreams,
    private val frontends: Frontends,
) : YtDlpProvider(ytDlp) {
    override val id = "youtube"
    override val displayName = "YouTube"

    /**
     * youtube music's own search api answers in under a second, yt-dlp's ytsearch takes a few
     * (it boots python every time). yt-dlp stays as the fallback if the api ever changes.
     */
    override suspend fun search(query: String, limit: Int): List<Track> = searchHits(query, limit).map { it.track }

    override suspend fun searchHits(query: String, limit: Int, tag: String?): List<SearchHit> =
        runCatching { music.searchHits(tag ?: query, limit) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: flatSearch("ytsearch$limit:${tag ?: query}").map { SearchHit(it) }

    override suspend fun matchCandidates(query: String, title: String?): List<SearchHit> =
        runCatching { music.matchCandidates(query) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: flatSearch("ytsearch10:$query").map { SearchHit(it) }

    override suspend fun searchArtists(query: String, limit: Int) = music.searchArtists(query, limit)

    private companion object {
        const val YTDLP_AFTER_MS = 12_000L
    }

    private val fastFailures = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var fastRetryAt = 0L

    /** videos whose quick link broke while playing: NewPipe / yt-dlp only, until the app restarts */
    private val slowOnly = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun preferYtDlp(sourceId: String) {
        slowOnly += sourceId
    }

    /** youtube's own player api first (one request), yt-dlp when youtube asks for more */
    override suspend fun resolveStream(locator: Locator): StreamSource {
        val videoId = Regex("""[?&]v=([A-Za-z0-9_-]{11})""").find(locator.sourceId)?.groupValues?.get(1)
            ?: Regex("""youtu\.be/([A-Za-z0-9_-]{11})""").find(locator.sourceId)?.groupValues?.get(1)
        if (videoId == null) return super.resolveStream(locator)
        val url = "https://www.youtube.com/watch?v=$videoId"
        // youtube's quick way (skipped for a while after it keeps refusing) and NewPipe (java,
        // a second or two) start together; yt-dlp (a whole python, 30s+ on some phones) only
        // joins if neither has answered in a few seconds. first one with a link wins.
        // a song whose quick link was cut off mid-play goes NewPipe → yt-dlp instead
        val quickBlocked = locator.sourceId in slowOnly ||
            (fastFailures.get() >= 3 && System.currentTimeMillis() < fastRetryAt)
        val fast = racer {
            if (quickBlocked) null
            else runCatching { music.audioStream(videoId) }.getOrNull().also { r ->
                if (r != null) fastFailures.set(0)
                else if (fastFailures.incrementAndGet() >= 3) fastRetryAt = System.currentTimeMillis() + 10 * 60 * 1000L
            }
        }
        var pipeWhy = "no link"
        val pipe = racer {
            runCatching { newPipe.audioStream(url) }
                .onFailure { pipeWhy = "${it.javaClass.simpleName}: ${it.message.orEmpty()}".take(90) }
                .getOrNull()
                .also { if (it == null && pipeWhy == "no link") pipeWhy = "link refused (403)" }
        }
        // community frontends (invidious / piped / yt2009): they fetch from youtube themselves
        val mirror = racer { frontends.audioStream(videoId) }
        val quick = listOf(fast, pipe, mirror)
        var slow: kotlinx.coroutines.Deferred<Result<StreamSource>>? = null
        try {
            firstNonNull(quick, YTDLP_AFTER_MS)?.let { return it }
            val s = raceScope.async { runCatching { super.resolveStream(locator) } }
            slow = s
            val result = firstNonNull(quick + raceScope.async { s.await().getOrNull() })
            // when yt-dlp had to do it, say why the quick ways didn't (settings → playback log)
            val why = " (quick: ${if (quickBlocked) "skipped" else "refused"}; NewPipe: $pipeWhy)"
            return (result ?: s.await().getOrThrow()).let { if (it.via == "yt-dlp") it.copy(via = it.via + why) else it }
        } finally {
            quick.forEach { it.cancel() }
            slow?.cancel()
        }
    }

    override suspend fun artistTracks(artistId: String, limit: Int): List<Track> =
        music.artist(artistId)?.popular.orEmpty().take(limit)

    override suspend fun artistDetails(artistId: String) = music.artist(artistId)

    override fun entryToTrack(o: JsonObject, fallbackUrl: String?): Track? {
        val videoId = o.str("id") ?: return super.entryToTrack(o, fallbackUrl)
        // flat entries have a bare id; normalise to one url form so ids stay stable
        val url = "https://www.youtube.com/watch?v=$videoId"
        return super.entryToTrack(o, url)?.copy(id = Track.makeId(id, url), sourceId = url)
    }

    override fun artwork(o: JsonObject): String? =
        o.str("id")?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" } ?: super.artwork(o)
}

@Singleton
class SoundCloudProvider @Inject constructor(ytDlp: YtDlp, private val api: SoundCloudApi) : YtDlpProvider(ytDlp) {
    override val id = "soundcloud"
    override val displayName = "SoundCloud"

    override suspend fun search(query: String, limit: Int) = searchHits(query, limit).map { it.track }

    /** soundcloud's api first (fast, tags + plays), yt-dlp's scsearch if the api is down */
    override suspend fun searchHits(query: String, limit: Int, tag: String?): List<SearchHit> = runCatching {
        val params = buildMap {
            put("q", if (tag != null) "" else query)
            put("limit", "$limit")
            if (tag != null) put("filter.genre_or_tag", tag)
        }
        api.get("/search/tracks", params)["collection"].asObjectList().mapNotNull(api::hitFrom)
    }.getOrElse { flatSearch("scsearch$limit:${tag ?: query}").map { SearchHit(it) } }

    /**
     * soundcloud stopped offering plain mp3 streams, it's hls only; the queue marks soundcloud
     * songs as hls (see PlaybackService.uriFor) so the player loads this playlist right
     */
    override suspend fun resolveStream(locator: Locator): StreamSource {
        val track = api.get("/resolve", mapOf("url" to locator.sourceId))
        val auth = track.str("track_authorization")
        val transcodings = track.obj("media")?.get("transcodings").asObjectList()
        // a plain mp3 file when soundcloud still has one (plays like any file), else hls
        val progressive = transcodings.firstOrNull { it.obj("format")?.str("protocol") == "progressive" }
        val hls = transcodings.filter { it.obj("format")?.str("protocol") == "hls" }
        val pick = progressive
            ?: hls.firstOrNull { it.obj("format")?.str("mime_type")?.contains("mp4a") == true } ?: hls.firstOrNull()
            ?: throw ProviderException("SoundCloud has no stream for this one")
        val path = pick.str("url")!!.removePrefix("https://api-v2.soundcloud.com")
        val url = api.get(path, buildMap { if (auth != null) put("track_authorization", auth) }).str("url")
            ?: throw ProviderException("SoundCloud didn't give a stream")
        return StreamSource(url, via = "SoundCloud", hls = pick !== progressive)
    }

    override suspend fun searchArtists(query: String, limit: Int): List<RemoteArtist> =
        api.get("/search/users", mapOf("q" to query, "limit" to "$limit"))["collection"].asObjectList().mapNotNull(api::artistFrom)

    override suspend fun artistDetails(artistId: String): ArtistDetails? = coroutineScope {
        val user = async { api.get("/users/$artistId") }
        val top = async { runCatching { api.get("/users/$artistId/toptracks", mapOf("limit" to "10"))["collection"].asObjectList() }.getOrDefault(emptyList()) }
        val all = async { runCatching { artistTracks(artistId, 50) }.getOrDefault(emptyList()) }
        val related = async {
            runCatching { api.get("/users/$artistId/relatedartists", mapOf("limit" to "12"))["collection"].asObjectList() }.getOrDefault(emptyList())
        }
        val u = user.await()
        ArtistDetails(
            provider = id,
            id = artistId,
            name = u.str("username") ?: return@coroutineScope null,
            avatarUrl = u.str("avatar_url")?.replace("-large.", "-t500x500."),
            bannerUrl = u.obj("visuals")?.get("visuals").asObjectList().firstOrNull()?.str("visual_url"),
            bio = u.str("description"),
            followers = u.num("followers_count")?.toLong(),
            location = listOfNotNull(u.str("city"), u.str("country_code")).joinToString(", ").ifBlank { null },
            link = u.str("permalink_url"),
            popular = (top.await().mapNotNull { api.hitFrom(it)?.track } + all.await()).distinctBy { it.id },
            similar = related.await().mapNotNull { (it.obj("user") ?: it).let(api::artistFrom) },
        )
    }

    override suspend fun artistTracks(artistId: String, limit: Int): List<Track> =
        api.get("/users/$artistId/tracks", mapOf("limit" to "$limit"))["collection"].asObjectList()
            .mapNotNull { api.hitFrom(it)?.track }

    override fun entryToTrack(o: JsonObject, fallbackUrl: String?): Track? {
        return super.entryToTrack(o, fallbackUrl) ?: run {
            // some flat entries only carry a url; make a title out of the slug so it's still usable
            val url = o.str("url") ?: return null
            val slug = url.trimEnd('/').substringAfterLast('/')
            val user = url.removePrefix("https://soundcloud.com/").substringBefore('/')
            Track(
                id = Track.makeId(id, url), title = slug.replace('-', ' '), artist = user.replace('-', ' '),
                provider = id, sourceId = url,
            )
        }
    }
}

/**
 * yt-dlp can play bandcamp but can't search it, so search goes through bandcamp's own
 * autocomplete api (the one the site's search box uses).
 */
@Singleton
class BandcampProvider @Inject constructor(ytDlp: YtDlp, private val http: OkHttpClient) : YtDlpProvider(ytDlp) {
    override val id = "bandcamp"
    override val displayName = "Bandcamp"

    override suspend fun search(query: String, limit: Int): List<Track> = httpIo {
        val body = """{"search_text":${JsonPrimitive(query)},"search_filter":"t","full_page":false,"fan_id":null}"""
        val req = Request.Builder()
            .url("https://bandcamp.com/api/bcsearch_public_api/1/autocomplete_elastic")
            .header("User-Agent", BROWSER_UA)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val text = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ProviderException("Bandcamp search failed (${r.code})")
            r.body!!.string()
        }
        val results = lenientJson.parseToJsonElement(text).jsonObject.obj("auto")?.arr("results").orEmpty()
        results.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            if (o.str("type") != "t") return@mapNotNull null
            val url = o.str("item_url_path") ?: o.str("item_url_root") ?: return@mapNotNull null
            Track(
                id = Track.makeId(id, url),
                title = o.str("name") ?: return@mapNotNull null,
                artist = o.str("band_name") ?: "",
                album = o.str("album_name"),
                artworkUrl = o.str("img"),
                provider = id,
                sourceId = url,
            )
        }.take(limit)
    }
}

internal const val BROWSER_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

internal suspend fun <T> httpIo(block: () -> T): T =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }

internal fun OkHttpClient.getText(url: String, headers: Map<String, String> = emptyMap()): String {
    val req = Request.Builder().url(url).header("User-Agent", BROWSER_UA).apply {
        headers.forEach { (k, v) -> header(k, v) }
    }.build()
    return newCall(req).execute().use { r ->
        if (!r.isSuccessful) throw ProviderException("${req.url.host} said ${r.code}")
        r.body!!.string()
    }
}

internal fun JsonElement?.asObjectList(): List<JsonObject> =
    (this as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
