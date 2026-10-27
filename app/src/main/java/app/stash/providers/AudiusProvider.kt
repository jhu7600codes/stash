package app.stash.providers

import app.stash.data.db.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * audius: a free, open streaming service built for independent artists, which makes it the
 * best place for underground stuff. public api, no key; streams are plain mp3 (a redirect).
 */
@Singleton
class AudiusProvider @Inject constructor(private val http: OkHttpClient) : MusicProvider {
    override val id = "audius"
    override val displayName = "Audius"

    private suspend fun api(path: String, params: Map<String, String> = emptyMap()): List<JsonObject> {
        val url = "$HOST/v1$path".toHttpUrl().newBuilder()
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .addQueryParameter("app_name", APP)
            .build()
        return lenientJson.parseToJsonElement(httpIo { http.getText(url.toString()) }).jsonObject["data"].asObjectList()
    }

    private fun streamUrl(trackId: String) = "$HOST/v1/tracks/$trackId/stream?app_name=$APP"

    private fun hitFrom(o: JsonObject): SearchHit? {
        val trackId = o.str("id") ?: return null
        val user = o.obj("user")
        val track = Track(
            id = Track.makeId(id, trackId),
            title = o.str("title") ?: return null,
            artist = user?.str("name") ?: "",
            durationMs = ((o.num("duration") ?: 0.0) * 1000).toLong(),
            artworkUrl = o.obj("artwork")?.let { it.str("480x480") ?: it.str("1000x1000") ?: it.str("150x150") },
            provider = id,
            sourceId = trackId,
            streamHint = streamUrl(trackId),
        )
        val tags = o.str("tags").orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() } +
            listOfNotNull(o.str("genre")?.lowercase(), o.str("mood")?.lowercase())
        val uploader = user?.let { u ->
            val uid = u.str("id") ?: return@let null
            RemoteArtist(
                provider = id, id = uid, name = u.str("name") ?: return@let null,
                avatarUrl = u.obj("profile_picture")?.let { it.str("480x480") ?: it.str("150x150") },
                followers = u.num("follower_count")?.toLong(),
            )
        }
        return SearchHit(track, o.num("play_count")?.toLong(), tags.distinct(), uploader)
    }

    override suspend fun search(query: String, limit: Int) = searchHits(query, limit).map { it.track }

    override suspend fun searchHits(query: String, limit: Int, tag: String?): List<SearchHit> {
        val hits = api("/tracks/search", mapOf("query" to (tag ?: query), "limit" to "${limit * 2}")).mapNotNull(::hitFrom)
        // the search matches tags loosely; for a #tag search keep the ones really tagged with it
        return (if (tag != null) hits.filter { h -> h.tags.any { it.contains(tag) } }.ifEmpty { hits } else hits).take(limit)
    }

    override suspend fun searchArtists(query: String, limit: Int): List<RemoteArtist> =
        api("/users/search", mapOf("query" to query, "limit" to "$limit")).mapNotNull { u ->
            RemoteArtist(
                provider = id,
                id = u.str("id") ?: return@mapNotNull null,
                name = u.str("name") ?: return@mapNotNull null,
                avatarUrl = u.obj("profile_picture")?.let { it.str("480x480") ?: it.str("150x150") },
                followers = u.num("follower_count")?.toLong(),
            )
        }

    override suspend fun artistTracks(artistId: String, limit: Int): List<Track> =
        api("/users/$artistId/tracks", mapOf("limit" to "$limit", "sort" to "plays")).mapNotNull { hitFrom(it)?.track }

    override suspend fun artistDetails(artistId: String): ArtistDetails? = coroutineScope {
        val user = async {
            lenientJson.parseToJsonElement(httpIo { http.getText("$HOST/v1/users/$artistId?app_name=$APP") }).jsonObject.obj("data")
        }
        val tracks = async { runCatching { artistTracks(artistId, 50) }.getOrDefault(emptyList()) }
        val related = async { runCatching { api("/users/$artistId/related", mapOf("limit" to "12")) }.getOrDefault(emptyList()) }
        val u = user.await() ?: return@coroutineScope null
        ArtistDetails(
            provider = id,
            id = artistId,
            name = u.str("name") ?: return@coroutineScope null,
            avatarUrl = u.obj("profile_picture")?.let { it.str("480x480") ?: it.str("1000x1000") },
            bannerUrl = u.obj("cover_photo")?.let { it.str("2000x") ?: it.str("640x") },
            bio = u.str("bio"),
            followers = u.num("follower_count")?.toLong(),
            location = u.str("location"),
            link = u.str("handle")?.let { "https://audius.co/$it" },
            popular = tracks.await(),
            similar = related.await().mapNotNull { r ->
                RemoteArtist(
                    provider = id, id = r.str("id") ?: return@mapNotNull null, name = r.str("name") ?: return@mapNotNull null,
                    avatarUrl = r.obj("profile_picture")?.str("480x480"), followers = r.num("follower_count")?.toLong(),
                )
            },
        )
    }

    override suspend fun metadata(sourceId: String): Track? =
        runCatching { api("/tracks/$sourceId").firstOrNull() }.getOrNull()?.let { hitFrom(it)?.track }
            ?: runCatching {
                // single-track lookups answer with an object, not a list
                val url = "$HOST/v1/tracks/$sourceId?app_name=$APP"
                lenientJson.parseToJsonElement(httpIo { http.getText(url) }).jsonObject.obj("data")?.let { hitFrom(it)?.track }
            }.getOrNull()

    override suspend fun resolveStream(locator: Locator) = StreamSource(locator.streamHint ?: streamUrl(locator.sourceId))

    override suspend fun downloadSource(locator: Locator) =
        DownloadSource.Http(locator.streamHint ?: streamUrl(locator.sourceId), "mp3")

    companion object {
        private const val HOST = "https://api.audius.co"
        private const val APP = "stash"
    }
}
