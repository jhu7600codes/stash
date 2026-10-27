package app.stash.providers

import app.stash.data.db.Track
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * search through youtube music's internal (innertube) api, the same call music.youtube.com makes.
 * songs first (real artist / album / duration); where the songs catalog isn't available
 * it falls back to the videos tab, which still has the channel and duration.
 */
@Singleton
class YouTubeMusicSearch @Inject constructor(private val http: OkHttpClient) {

    suspend fun search(query: String, limit: Int): List<Track> = searchHits(query, limit).map { it.track }

    /** songs, or videos where the songs catalog isn't available, with view counts when shown */
    suspend fun searchHits(query: String, limit: Int): List<SearchHit> {
        val songs = request(query, SONGS).mapNotNull(::toHit)
        return songs.ifEmpty { request(query, VIDEOS).mapNotNull(::toHit) }.take(limit)
    }

    /**
     * songs and videos together, for matching imports: the songs tab only has label releases
     * (and isn't there at all in some countries), lots of underground music is only a video.
     */
    suspend fun matchCandidates(query: String): List<SearchHit> = kotlinx.coroutines.coroutineScope {
        val songs = async { runCatching { request(query, SONGS).mapNotNull(::toHit).map { it.copy(official = true) } }.getOrDefault(emptyList()) }
        val videos = async { runCatching { request(query, VIDEOS).mapNotNull(::toHit) }.getOrDefault(emptyList()) }
        (songs.await().take(8) + videos.await().take(12)).distinctBy { it.track.id }
    }

    /** youtube music's community playlists for [query] */
    suspend fun searchPlaylists(query: String, limit: Int = 12): List<RemotePlaylist> =
        request(query, PLAYLISTS).mapNotNull { item ->
            val browseId = item.obj("navigationEndpoint")?.obj("browseEndpoint")?.str("browseId")
                ?.takeIf { it.startsWith("VL") } ?: return@mapNotNull null
            RemotePlaylist(
                id = browseId,
                title = columnText(item, 0).joinToString("").ifBlank { return@mapNotNull null },
                subtitle = columnText(item, 1).joinToString("").ifBlank { null },
                coverUrl = item.obj("thumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.get("thumbnails")
                    .asObjectList().lastOrNull()?.str("url")?.replace(Regex("=w\\d+-h\\d+.*$"), "=w544-h544-l90-rj"),
            )
        }.take(limit)

    /** a playlist with its songs (up to [max], following youtube's pages of 100) */
    suspend fun playlist(browseId: String, max: Int = 1000): RemotePlaylist? {
        val id = if (browseId.startsWith("VL")) browseId else "VL$browseId"
        var root = browse(buildJsonObject { put("browseId", id) }) ?: return null
        val header = mutableListOf<JsonObject>().also { collectKey(root, "musicResponsiveHeaderRenderer", it) }.firstOrNull()
            ?: mutableListOf<JsonObject>().also { collectKey(root, "musicDetailHeaderRenderer", it) }.firstOrNull()
        val title = runsText(header?.obj("title")).ifBlank { "YouTube Music playlist" }
        val cover = header?.obj("thumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.get("thumbnails")
            .asObjectList().lastOrNull()?.str("url")?.replace(Regex("=[sw]\\d+.*$"), "=w544-h544-l90-rj")
        val subtitle = runsText(header?.obj("secondSubtitle")).ifBlank { runsText(header?.obj("subtitle")) }.ifBlank { null }
        val tracks = mutableListOf<Track>()
        while (true) {
            val rows = mutableListOf<JsonObject>().also { collectKey(root, "musicResponsiveListItemRenderer", it) }
            tracks += rows.mapNotNull { toTrack(it, splitTitle = false) }
            if (tracks.size >= max) break
            val token = continuationToken(root) ?: break
            root = browse(buildJsonObject { put("continuation", token) }) ?: break
        }
        return RemotePlaylist(id, title, subtitle, cover, tracks.distinctBy { it.id }.take(max))
    }

    private fun continuationToken(el: JsonElement): String? = when (el) {
        is JsonObject -> el.obj("continuationCommand")?.str("token")
            ?: el.obj("nextContinuationData")?.str("continuation")
            ?: el.values.firstNotNullOfOrNull(::continuationToken)
        is JsonArray -> el.firstNotNullOfOrNull(::continuationToken)
        else -> null
    }

    private suspend fun browse(extra: JsonObject): JsonObject? = httpIo {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", "en")
                }
            }
            extra.forEach { (k, v) -> put(k, v) }
        }.toString()
        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
            .header("User-Agent", BROWSER_UA)
            .header("Origin", "https://music.youtube.com")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            ?.let { lenientJson.parseToJsonElement(it).jsonObject }
    }

    /** artist results; the id is the artist's youtube music browse id (UC…) */
    suspend fun searchArtists(query: String, limit: Int): List<RemoteArtist> =
        request(query, ARTISTS).mapNotNull { item ->
            val name = columnText(item, 0).joinToString("").ifBlank { return@mapNotNull null }
            val sub = columnText(item, 1).joinToString("")
            val browseId = item.obj("navigationEndpoint")?.obj("browseEndpoint")?.str("browseId")
                ?: return@mapNotNull null
            RemoteArtist(
                provider = "youtube",
                id = browseId,
                name = name,
                avatarUrl = item.obj("thumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.get("thumbnails")
                    .asObjectList().lastOrNull()?.str("url")?.replace(Regex("=w\\d+-h\\d+.*$"), "=w400-h400-l90-rj"),
                followers = Regex("""([\d.,]+[KMB]?) (monthly audience|subscribers)""").find(sub)?.groupValues?.get(1)?.let(::parseCount),
            )
        }.take(limit)

    private suspend fun request(query: String, params: String): List<JsonObject> = httpIo {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", CLIENT_VERSION)
                    // in english youtube music spells russian artists in latin ("Zemfira"); ask in
                    // russian for cyrillic searches so names come back the way they were typed
                    put("hl", languageFor(query))
                }
            }
            put("query", query)
            put("params", params)
        }.toString()
        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
            .header("User-Agent", BROWSER_UA)
            .header("Origin", "https://music.youtube.com")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val text = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ProviderException("YouTube Music search failed (${r.code})")
            r.body!!.string()
        }
        val items = mutableListOf<JsonObject>()
        collect(lenientJson.parseToJsonElement(text), items)
        items
    }

    private fun toHit(item: JsonObject): SearchHit? {
        val track = toTrack(item) ?: return null
        val plays = columnText(item, 1).joinToString("").split(" • ")
            .firstOrNull { p -> VIEW_WORDS.any { p.contains(it) } }
            ?.let { p -> VIEW_WORDS.fold(p) { acc, w -> acc.replace(w, "") } }
            ?.let(::parseCount)
        val byline = columnText(item, 1).joinToString("").split(" • ").firstOrNull { it !in TYPE_LABELS }.orEmpty()
        return SearchHit(track, plays, byline = "$byline ${columnText(item, 0).joinToString("")}")
    }

    private fun languageFor(query: String) = if (query.any { it in '\u0400'..'\u04FF' }) "ru" else "en"

    /**
     * youtube music's radio for a song: the "up next" queue its own app builds, ~50 similar
     * songs. this is what all the recommendations and mixes are made from.
     */
    suspend fun radio(videoId: String): List<Track> = httpIo {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", "en")
                }
            }
            put("videoId", videoId)
            put("playlistId", "RDAMVM$videoId")
            put("isAudioOnly", true)
        }.toString()
        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/next?prettyPrint=false")
            .header("User-Agent", BROWSER_UA)
            .header("Origin", "https://music.youtube.com")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val text = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ProviderException("YouTube Music radio failed (${r.code})")
            r.body!!.string()
        }
        val items = mutableListOf<JsonObject>()
        collectKey(lenientJson.parseToJsonElement(text), "playlistPanelVideoRenderer", items)
        items.mapNotNull(::radioTrack).filter { it.sourceId != watchUrl(videoId) }.distinctBy { it.id }
    }

    /**
     * the audio stream straight from youtube's player api, as the android VR app asks for it:
     * those urls need no signature solving, so this takes one http request instead of starting
     * yt-dlp's whole python (seconds on a phone). null when youtube wants more (bot check, age
     * gate…); yt-dlp handles those.
     */
    suspend fun audioStream(videoId: String): StreamSource? =
        // every client at once, first link wins: one after the other, a refusing first client
        // (vr, on some connections always) cost seconds on every song
        race(PLAYER_CLIENTS.map { c -> suspend { audioStream(videoId, c) } })

    private class PlayerClient(val name: String, val id: Int, val version: String, val ua: String, val extra: Map<String, Any>)

    private suspend fun audioStream(videoId: String, client: PlayerClient): StreamSource? = httpIo {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", client.name)
                    put("clientVersion", client.version)
                    client.extra.forEach { (k, v) -> if (v is Int) put(k, v) else put(k, v.toString()) }
                    put("hl", "en")
                }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }.toString()
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", client.ua)
            .header("X-YouTube-Client-Name", client.id.toString())
            .header("X-YouTube-Client-Version", client.version)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val text = http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null } ?: return@httpIo null
        val root = lenientJson.parseToJsonElement(text).jsonObject
        if (root.obj("playabilityStatus")?.str("status") != "OK") return@httpIo null
        val audio = root.obj("streamingData")?.get("adaptiveFormats").asObjectList()
            .filter { it.str("mimeType").orEmpty().startsWith("audio/") && it.str("url") != null }
        // opus (251) is the best sounding, m4a (140) plays everywhere
        val best = audio.firstOrNull { it.num("itag")?.toInt() == 251 }
            ?: audio.firstOrNull { it.num("itag")?.toInt() == 140 }
            ?: audio.maxByOrNull { it.num("bitrate") ?: 0.0 }
            ?: return@httpIo null
        val url = best.str("url")!!
        // youtube sometimes hands out links that answer 403 once played (it wants a token yt-dlp
        // knows how to get); ask for the first two bytes so those fall through to the next client
        val ok = StreamCheck.plays(http, url, client.ua)
        if (ok) StreamSource(url, mapOf("User-Agent" to client.ua), via = "YouTube fast (${client.name.lowercase()})") else null
    }

    /** an artist's youtube music page: banner, bio, audience, top songs/videos, "fans also like" */
    suspend fun artist(browseId: String): ArtistDetails? = httpIo {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", "en")
                }
            }
            put("browseId", browseId)
        }.toString()
        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
            .header("User-Agent", BROWSER_UA)
            .header("Origin", "https://music.youtube.com")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val text = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ProviderException("YouTube Music artist page failed (${r.code})")
            r.body!!.string()
        }
        val root = lenientJson.parseToJsonElement(text).jsonObject
        val header = root.obj("header")?.let { h -> (h.values.firstOrNull() as? JsonObject) } ?: return@httpIo null
        val name = runsText(header.obj("title")).ifBlank { return@httpIo null }
        val pictures = header.obj("thumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.get("thumbnails").asObjectList()
        val banner = pictures.lastOrNull()?.str("url")

        // top songs come as list rows, videos / singles as cards; both are songs to us
        val rows = mutableListOf<JsonObject>()
        collectKey(root.obj("contents") ?: root, "musicResponsiveListItemRenderer", rows)
        val cards = mutableListOf<JsonObject>()
        collectKey(root.obj("contents") ?: root, "musicTwoRowItemRenderer", cards)
        val songs = rows.mapNotNull(::toTrack) + cards.mapNotNull { c -> cardTrack(c, name) }
        val similar = cards.mapNotNull { c ->
            val id = c.obj("navigationEndpoint")?.obj("browseEndpoint")?.str("browseId")?.takeIf { it.startsWith("UC") && it != browseId }
                ?: return@mapNotNull null
            RemoteArtist(
                provider = "youtube", id = id, name = runsText(c.obj("title")).ifBlank { return@mapNotNull null },
                avatarUrl = c.obj("thumbnailRenderer")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.get("thumbnails")
                    .asObjectList().lastOrNull()?.str("url"),
                followers = runsText(c.obj("subtitle")).substringBefore(" ").let(::parseCount),
            )
        }.distinctBy { it.id }
        ArtistDetails(
            provider = "youtube",
            id = browseId,
            name = name,
            avatarUrl = banner?.replace(Regex("=w\\d+-h\\d+.*$"), "=w600-h600-p-l90-rj"),
            bannerUrl = banner,
            bio = runsText(header.obj("description")).ifBlank { null },
            followers = header.obj("subscriptionButton")?.obj("subscribeButtonRenderer")?.obj("subscriberCountText")
                ?.let(::runsText)?.let(::parseCount),
            listeners = runsText(header.obj("monthlyListenerCount")).ifBlank { null },
            link = "https://music.youtube.com/channel/$browseId",
            popular = songs.distinctBy { it.id },
            similar = similar,
        )
    }

    /** a video/single card on an artist page ("Creep" · "Radiohead • 1.5B views") */
    private fun cardTrack(c: JsonObject, artist: String): Track? {
        val videoId = c.obj("navigationEndpoint")?.obj("watchEndpoint")?.str("videoId") ?: return null
        val title = runsText(c.obj("title")).ifBlank { return null }
        val url = watchUrl(videoId)
        val (a, t) = YtDlpProvider.splitArtistTitle(title, artist, hasTrackField = false)
        return Track(
            id = Track.makeId("youtube", url), title = t, artist = a, provider = "youtube", sourceId = url,
            artworkUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
        )
    }

    private fun runsText(o: JsonObject?): String =
        o?.get("runs").asObjectList().joinToString("") { (it["text"] as? JsonPrimitive)?.content.orEmpty() }

    private fun radioTrack(r: JsonObject): Track? {
        val videoId = r.str("videoId") ?: return null
        val title = runsText(r.obj("title")).ifBlank { return null }
        // "Artist • Album • 2019" for songs, "Channel • 1.5B views • 8M likes" for videos
        val parts = runsText(r.obj("longBylineText")).split(" • ").map { it.trim() }
        val byline = parts.firstOrNull().orEmpty()
        val album = parts.getOrNull(1)?.takeUnless { p -> VIEW_WORDS.any { p.contains(it) } || p.endsWith("likes") || p.contains("отметок") || p.all(Char::isDigit) }
        val (artist, cleanTitle) = YtDlpProvider.splitArtistTitle(title, byline, hasTrackField = false)
        val thumb = r.obj("thumbnail")?.get("thumbnails").asObjectList().lastOrNull()?.str("url")
        val url = watchUrl(videoId)
        return Track(
            id = Track.makeId("youtube", url),
            title = cleanTitle,
            artist = artist,
            album = album,
            durationMs = runsText(r.obj("lengthText")).takeIf { DURATION.matches(it) }?.let(::parseDuration) ?: 0,
            artworkUrl = thumb?.replace(Regex("=w\\d+-h\\d+.*$"), "=w544-h544-l90-rj") ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            provider = "youtube",
            sourceId = url,
        )
    }

    private fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

    private fun collectKey(el: JsonElement, key: String, out: MutableList<JsonObject>) {
        when (el) {
            is JsonObject -> {
                val item = el[key] as? JsonObject
                if (item != null) out += item else el.values.forEach { collectKey(it, key, out) }
            }
            is JsonArray -> el.forEach { collectKey(it, key, out) }
            else -> Unit
        }
    }

    private fun collect(el: JsonElement, out: MutableList<JsonObject>) {
        when (el) {
            is JsonObject -> {
                val item = el["musicResponsiveListItemRenderer"] as? JsonObject
                if (item != null) out += item else el.values.forEach { collect(it, out) }
            }
            is JsonArray -> el.forEach { collect(it, out) }
            else -> Unit
        }
    }

    private fun columnText(item: JsonObject, i: Int): List<String> =
        (item["flexColumns"] as? JsonArray)?.getOrNull(i)?.jsonObject
            ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.get("runs").asObjectList()
            .mapNotNull { (it["text"] as? JsonPrimitive)?.content }

    /** [splitTitle]: "Artist - Song" video titles get split; off for playlist rows, which already name the artist */
    private fun toTrack(item: JsonObject, splitTitle: Boolean = true): Track? {
        val videoId = item.obj("playlistItemData")?.str("videoId") ?: return null
        val title = columnText(item, 0).joinToString("").ifBlank { return null }
        // "Artist • Album • 3:58" for songs, "Channel • 1.5B views • 3:57" for videos,
        // sometimes with a leading "Song" / "Video" label
        val parts = columnText(item, 1).joinToString("").split(" • ").map { it.trim() }.filter { it.isNotEmpty() }
            .dropWhile { it in TYPE_LABELS }
        // playlist rows keep the length in a separate fixed column...
        val fixed = (item["fixedColumns"] as? JsonArray)?.mapNotNull { c ->
            (c as? JsonObject)?.obj("musicResponsiveListItemFixedColumnRenderer")?.obj("text")?.let(::runsText)
        }.orEmpty()
        // ...or in a third flex column, depending on the layout youtube sends
        val later = (2..3).flatMap { columnText(item, it) }.map { it.trim() }
        val duration = parts.lastOrNull { DURATION.matches(it) } ?: fixed.firstOrNull { DURATION.matches(it) }
            ?: later.firstOrNull { DURATION.matches(it) }
        val rest = parts.filter { p -> p != duration && VIEW_WORDS.none { p.contains(it) } }
        val artistRaw = rest.firstOrNull() ?: ""
        val album = rest.getOrNull(1)
        val url = "https://www.youtube.com/watch?v=$videoId"
        // videos: titles are often "Artist - Song (Official Video)", reuse the yt-dlp heuristics
        val (artist, cleanTitle) = if (album == null && splitTitle) {
            YtDlpProvider.splitArtistTitle(title, artistRaw, hasTrackField = false)
        } else artistRaw to title
        val thumb = item.obj("thumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.get("thumbnails")
            .asObjectList().lastOrNull()?.str("url")
        return Track(
            id = Track.makeId("youtube", url),
            title = cleanTitle,
            artist = artist,
            album = album,
            durationMs = duration?.let(::parseDuration) ?: 0,
            // song thumbnails come at 60px, ask googleusercontent for a bigger square
            artworkUrl = thumb?.replace(Regex("=w\\d+-h\\d+.*$"), "=w544-h544-l90-rj")
                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            provider = "youtube",
            sourceId = url,
        )
    }

    /** "1.5B" -> 1_500_000_000; also russian "51 млн" / "93 тыс." / "1,2 млрд" */
    private fun parseCount(s: String): Long? {
        val t = s.replace('\u00a0', ' ').trim().lowercase()
        val mult = when {
            t.endsWith("b") || t.contains("млрд") -> 1e9
            t.endsWith("m") || t.contains("млн") -> 1e6
            t.endsWith("k") || t.contains("тыс") -> 1e3
            else -> 1.0
        }
        val number = Regex("""[\d.,]+""").find(t)?.value ?: return null
        // russian uses a comma for decimals ("9,5"), english a comma for thousands ("1,234")
        val n = if (mult > 1.0) number.replace(',', '.') else number.replace(",", "")
        return n.toDoubleOrNull()?.let { (it * mult).toLong() }
    }

    private fun parseDuration(s: String): Long =
        s.split(':').fold(0L) { acc, p -> acc * 60 + (p.toLongOrNull() ?: 0) } * 1000

    companion object {
        private const val CLIENT_VERSION = "1.20250929.03.00"
        /** youtube's own apps whose stream links need no signature solving, tried in order */
        private val PLAYER_CLIENTS = listOf(
            PlayerClient(
                "ANDROID_VR", 28, "1.60.19",
                "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                mapOf("deviceMake" to "Oculus", "deviceModel" to "Quest 3", "androidSdkVersion" to 32, "osName" to "Android", "osVersion" to "12L"),
            ),
        )
        // search filter tabs, as music.youtube.com's chips send them
        private const val SONGS = "EgWKAQIIAWoMEAQQBRAQEBUQChAR"
        private const val VIDEOS = "EgWKAQIQAWoMEAQQBRAQEBUQChAR"
        private const val ARTISTS = "EgWKAQIgAWoMEAQQBRAQEBUQChAR"
        private const val PLAYLISTS = "EgeKAQQoAEABagoQBBAFEBUQChAQ"
        private val TYPE_LABELS = setOf("Song", "Video", "Песня", "Видео", "Трек")
        private val VIEW_WORDS = listOf(" views", " plays", " просмотров", " просмотра", " просмотр", " прослушиваний")
        private val DURATION = Regex("""\d{1,2}(:\d{2}){1,2}""")
    }
}
