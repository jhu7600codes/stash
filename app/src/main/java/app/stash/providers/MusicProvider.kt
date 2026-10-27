package app.stash.providers

import app.stash.data.db.Track

/** a direct audio url plus whatever headers the host wants on the request */
data class StreamSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** how it was found, for the playback log ("youtube fast", "yt-dlp", "downloaded"…) */
    val via: String = "",
    /** an hls playlist (soundcloud), which the player has to open as one, not as a plain file */
    val hls: Boolean = false,
)

sealed interface DownloadSource {
    /** plain http file, downloaded with okhttp (range requests for resume) */
    data class Http(val url: String, val extension: String, val headers: Map<String, String> = emptyMap()) : DownloadSource

    /** let yt-dlp fetch it, it handles resume and throttling itself */
    data class YtDlp(val pageUrl: String) : DownloadSource
}

/**
 * One place music can come from. Adding a source = one new class implementing this and one
 * line in [ProviderRegistry].
 *
 * Methods get a [Locator] instead of a [Track] because the resolver also calls them with the
 * fallback location cached on a track (resolvedProvider/resolvedSourceId).
 */
interface MusicProvider {
    val id: String
    val displayName: String

    /** false when the provider needs setup (an api key) the user hasn't given yet */
    suspend fun isConfigured(): Boolean = true

    suspend fun search(query: String, limit: Int = 20): List<Track>

    /**
     * search with the extra bits the search screen ranks by: play counts (for the underground
     * filter) and tags. [tag] set means "songs tagged this" (from #tag searches), where the
     * source supports it. sources without those details just wrap [search].
     */
    suspend fun searchHits(query: String, limit: Int = 20, tag: String? = null): List<SearchHit> =
        search(query, limit).map { SearchHit(it) }

    /**
     * results to pick an imported song from. youtube overrides it to look at both songs and
     * videos, since underground songs are often only uploaded as videos. [title] is the bare
     * song title, for sources that only search song names (vocadb).
     */
    suspend fun matchCandidates(query: String, title: String? = null): List<SearchHit> = searchHits(query, 10)

    /** artists matching [query]; empty for sources without artist pages */
    suspend fun searchArtists(query: String, limit: Int = 8): List<RemoteArtist> = emptyList()

    /** an artist's own tracks, for the artist page opened from search */
    suspend fun artistTracks(artistId: String, limit: Int = 50): List<Track> = emptyList()

    /** everything for an artist page: picture, banner, bio, popular songs, similar artists */
    suspend fun artistDetails(artistId: String): ArtistDetails? = null

    /** called right before playback. never cache the result for long, yt-dlp urls expire */
    suspend fun resolveStream(locator: Locator): StreamSource

    /** fresh metadata for a single item, or null if it's gone */
    suspend fun metadata(sourceId: String): Track?

    suspend fun downloadSource(locator: Locator): DownloadSource
}

/** a search result plus what we know about how big it is and how it's tagged */
data class SearchHit(
    val track: Track,
    val plays: Long? = null,
    val tags: List<String> = emptyList(),
    /** who uploaded it, when the source says (soundcloud / audius), for the artists row */
    val uploader: RemoteArtist? = null,
    /** the channel and raw upload title, for the matcher ("LSPLASH" behind "DOORS OST - Here I Come") */
    val byline: String? = null,
    /** youtube music's "songs" tab: the label's own audio, not someone's video */
    val official: Boolean = false,
)

/** an artist on some source (soundcloud user, audius user, youtube channel) */
data class RemoteArtist(
    val provider: String,
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val followers: Long?,
)

data class ArtistDetails(
    val provider: String,
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val bannerUrl: String?,
    val bio: String?,
    val followers: Long?,
    /** e.g. "205M monthly audience", when the source says */
    val listeners: String? = null,
    val location: String? = null,
    /** the artist's page on the source */
    val link: String? = null,
    val popular: List<Track> = emptyList(),
    val similar: List<RemoteArtist> = emptyList(),
)

/** a youtube music playlist (community / featured), for search and its own page */
data class RemotePlaylist(
    /** youtube music browse id, "VL" + the playlist id */
    val id: String,
    val title: String,
    val subtitle: String?,
    val coverUrl: String?,
    val tracks: List<Track> = emptyList(),
)

data class Locator(val provider: String, val sourceId: String, val streamHint: String?)

val Track.primaryLocator get() = Locator(provider, sourceId, streamHint)

val Track.fallbackLocator: Locator?
    get() = if (resolvedProvider != null && resolvedSourceId != null) {
        Locator(resolvedProvider, resolvedSourceId, resolvedStreamHint)
    } else null

class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)
