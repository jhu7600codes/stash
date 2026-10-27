package app.stash.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class DownloadState { NONE, QUEUED, DOWNLOADING, DONE, FAILED }

/**
 * One song from any provider. Search results use this too before they're saved, so every
 * library column has a default. [id] is "provider:sourceId" so the same song from the same
 * provider never shows up twice.
 */
@Entity(tableName = "tracks", indices = [Index("artist"), Index("album"), Index("lastPlayedAt")])
data class Track(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0,
    val artworkUrl: String? = null,
    /** which [app.stash.providers.MusicProvider] this came from */
    val provider: String,
    /** provider-specific locator: a page url for yt-dlp sources, an api id otherwise */
    val sourceId: String,
    /** a stable (non-expiring) direct audio url when the provider has one, e.g. jamendo or archive.org */
    val streamHint: String? = null,

    // fallback: the provider that actually worked last time the original one failed
    val resolvedProvider: String? = null,
    val resolvedSourceId: String? = null,
    val resolvedStreamHint: String? = null,

    // offline
    val localPath: String? = null,
    val downloadState: DownloadState = DownloadState.NONE,
    val downloadProgress: Float = 0f,
    val downloadError: String? = null,

    // library
    val inLibrary: Boolean = false,
    val liked: Boolean = false,
    val addedAt: Long = 0,
    /** liked songs are listed newest-liked first; imports set this to keep spotify's order */
    @ColumnInfo(defaultValue = "0") val likedAt: Long = 0,
    val lastPlayedAt: Long? = null,
    val playCount: Int = 0,
) {
    val isDownloaded get() = downloadState == DownloadState.DONE && localPath != null

    companion object {
        fun makeId(provider: String, sourceId: String) = "$provider:$sourceId"
    }
}

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String? = null,
    val coverUrl: String? = null,
    /** "local", "spotify" or "apple" */
    val origin: String = "local",
    val originId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** keyed by position, so a song can sit in a playlist twice, same as on spotify */
@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "position"],
    indices = [Index("trackId")],
)
data class PlaylistTrack(
    val playlistId: Long,
    val trackId: String,
    val position: Int,
)

/** an imported song none of the providers could find, kept so it can be fixed by hand */
@Entity(tableName = "unmatched", indices = [Index("playlistId")])
data class UnmatchedTrack(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0,
    val position: Int,
    /** for liked songs: the likedAt it would have had, so a hand fix lands in the right spot */
    @ColumnInfo(defaultValue = "0") val likedAt: Long = 0,
)

data class PlaylistEntry(
    @Embedded val track: Track,
    val position: Int,
)

data class PlaylistWithCount(
    val id: Long,
    val name: String,
    val description: String?,
    val coverUrl: String?,
    val origin: String,
    val originId: String?,
    val updatedAt: Long,
    val trackCount: Int,
    val downloadedCount: Int,
    /** first track's art, used as the cover when the playlist has none */
    val firstArtwork: String?,
) {
    /** what to draw as its cover: a made-for-you design for mixes, else its cover, else the first song's */
    val art: Any? get() = MixCover.of(origin, originId, name, coverUrl) ?: coverUrl ?: firstArtwork
}

/**
 * a made-for-you cover drawn by the app, spotify style (see ui MixArt): "Daily Mix 2" band,
 * "This is <artist>", a genre over an artist photo. [photo] is the artist picture, if any.
 */
data class MixCover(val mixId: String, val title: String, val photo: String?) {
    companion object {
        fun of(origin: String, originId: String?, name: String, photo: String?): MixCover? =
            if (origin == "mix" && originId != null) MixCover(originId, name, photo) else null
    }
}

data class AlbumSummary(
    val album: String,
    val artist: String,
    @ColumnInfo(name = "artworkUrl") val artworkUrl: String?,
    val trackCount: Int,
)

data class ArtistSummary(
    val artist: String,
    val artworkUrl: String?,
    val trackCount: Int,
)

/** an artist's main genre (from deezer), so genre mixes don't look it up every day */
@Entity(tableName = "artist_genres")
data class ArtistGenre(
    @PrimaryKey val artist: String,
    val genre: String?,
    val checkedAt: Long = System.currentTimeMillis(),
)

/** tracks the cover fixer already looked at, so it doesn't ask again every start */
@Entity(tableName = "cover_checks")
data class CoverCheck(
    @PrimaryKey val trackId: String,
    val checkedAt: Long = System.currentTimeMillis(),
)

/** unmatched songs from an imported liked songs list hang off this fake playlist id */
const val LIKED_PLAYLIST_ID = -1L

/** one playlist waiting to be (or being) moved into stash by [app.stash.importer.ImportWorker] */
@Entity(tableName = "import_jobs")
data class ImportJob(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val origin: String,
    val originId: String? = null,
    val coverUrl: String? = null,
    /** spotify liked songs land in stash's liked songs instead of a new playlist */
    val intoLiked: Boolean = false,
    val playlistId: Long? = null,
    /** queued, running, done, cancelled */
    val state: String = "queued",
    val total: Int = 0,
    val done: Int = 0,
    val matched: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "import_job_tracks", primaryKeys = ["jobId", "position"])
data class ImportJobTrack(
    val jobId: Long,
    val position: Int,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0,
    val artworkUrl: String? = null,
    @ColumnInfo(defaultValue = "NULL") val sourceUri: String? = null,
    /** pending, matched, unmatched */
    val state: String = "pending",
)

/**
 * "artist|title" -> the track it matched (or null for nothing found), so a song that's in
 * several of your playlists only gets searched once.
 */
@Entity(tableName = "match_cache")
data class MatchCache(
    @PrimaryKey val key: String,
    val trackId: String?,
    val checkedAt: Long = System.currentTimeMillis(),
)

/** an imported song and the cover spotify / the csv had for it */
data class CoverRepair(val trackId: String, val artworkUrl: String?, val sourceUri: String?, val album: String?)
