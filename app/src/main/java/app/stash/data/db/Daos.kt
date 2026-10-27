package app.stash.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun get(id: String): Track?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getAll(ids: List<String>): List<Track>

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun observe(id: String): Flow<Track?>

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    fun observeAll(ids: List<String>): Flow<List<Track>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(track: Track): Long

    /** a whole queue at once, in one transaction (one insert per song made big playlists slow to start) */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(tracks: List<Track>)

    @Update
    suspend fun update(track: Track)

    /** saves a search result without clobbering library/download state of an existing row */
    @Transaction
    suspend fun ensure(track: Track): Track {
        insertIgnore(track)
        return get(track.id) ?: track
    }

    @Query("UPDATE tracks SET inLibrary = 1, addedAt = CASE WHEN addedAt = 0 THEN :now ELSE addedAt END WHERE id = :id")
    suspend fun addToLibrary(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE tracks SET inLibrary = 0, liked = 0 WHERE id = :id")
    suspend fun removeFromLibrary(id: String)

    @Query("UPDATE tracks SET liked = :liked, likedAt = :now, inLibrary = CASE WHEN :liked THEN 1 ELSE inLibrary END, addedAt = CASE WHEN addedAt = 0 THEN :now ELSE addedAt END WHERE id = :id")
    suspend fun setLiked(id: String, liked: Boolean, now: Long = System.currentTimeMillis())

    /** imported likes: [likedAt] is picked so the list keeps the source's order */
    @Query("UPDATE tracks SET liked = 1, inLibrary = 1, likedAt = :likedAt, addedAt = CASE WHEN addedAt = 0 THEN :likedAt ELSE addedAt END WHERE id = :id")
    suspend fun importLike(id: String, likedAt: Long)

    @Query("UPDATE tracks SET artworkUrl = :art, album = COALESCE(album, :album) WHERE id = :id")
    suspend fun setCover(id: String, art: String, album: String?)

    @Query("UPDATE tracks SET lastPlayedAt = :now, playCount = playCount + 1 WHERE id = :id")
    suspend fun markPlayed(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE tracks SET resolvedProvider = :provider, resolvedSourceId = :sourceId, resolvedStreamHint = :hint WHERE id = :id")
    suspend fun setResolved(id: String, provider: String?, sourceId: String?, hint: String?)

    @Query("UPDATE tracks SET downloadState = :state, downloadProgress = :progress, downloadError = :error WHERE id = :id")
    suspend fun setDownloadState(id: String, state: DownloadState, progress: Float = 0f, error: String? = null)

    @Query("UPDATE tracks SET downloadProgress = :progress WHERE id = :id")
    suspend fun setDownloadProgress(id: String, progress: Float)

    @Query("UPDATE tracks SET downloadState = 'DONE', downloadProgress = 1, downloadError = NULL, localPath = :path, inLibrary = 1 WHERE id = :id")
    suspend fun markDownloaded(id: String, path: String)

    @Query("UPDATE tracks SET downloadState = 'NONE', downloadProgress = 0, downloadError = NULL, localPath = NULL WHERE id = :id")
    suspend fun clearDownload(id: String)

    @Query("SELECT * FROM tracks WHERE inLibrary = 1 ORDER BY addedAt DESC")
    fun observeLibrary(): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE liked = 1 ORDER BY likedAt DESC")
    fun observeLiked(): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE liked = 1 ORDER BY likedAt DESC")
    suspend fun liked(): List<Track>

    /** what you listen to, best first: plays count, likes count more */
    @Query(
        """SELECT * FROM tracks WHERE liked = 1 OR playCount > 0 OR inLibrary = 1
           ORDER BY (playCount * 2 + liked * 5 + inLibrary) DESC, lastPlayedAt DESC LIMIT :limit"""
    )
    suspend fun taste(limit: Int = 400): List<Track>

    /** songs you played a lot, but not lately */
    @Query("SELECT * FROM tracks WHERE playCount >= 3 AND lastPlayedAt < :before ORDER BY playCount DESC LIMIT :limit")
    suspend fun forgottenFavorites(before: Long, limit: Int = 30): List<Track>

    @Query("SELECT * FROM tracks WHERE liked = 1 ORDER BY likedAt DESC LIMIT 1")
    suspend fun lastLiked(): Track?

    @Query("SELECT * FROM tracks WHERE artist = :artist ORDER BY playCount DESC")
    suspend fun byArtist(artist: String): List<Track>

    @Query("SELECT * FROM tracks WHERE downloadState = 'DONE'")
    suspend fun downloaded(): List<Track>

    @Query("SELECT * FROM tracks WHERE downloadState = 'DONE' ORDER BY title COLLATE NOCASE")
    fun observeDownloaded(): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE downloadState IN ('QUEUED', 'DOWNLOADING', 'FAILED') ORDER BY downloadState = 'DOWNLOADING' DESC, downloadState = 'FAILED', addedAt")
    fun observeDownloadQueue(): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE downloadState IN ('QUEUED', 'DOWNLOADING')")
    suspend fun pendingDownloads(): List<Track>

    @Query("SELECT * FROM tracks WHERE downloadState = 'FAILED'")
    suspend fun failedDownloads(): List<Track>

    @Query("SELECT * FROM tracks WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE playCount > 0 ORDER BY playCount DESC, lastPlayedAt DESC LIMIT :limit")
    fun observeMostPlayed(limit: Int = 20): Flow<List<Track>>

    @Query(
        """SELECT album, artist, MAX(artworkUrl) AS artworkUrl, COUNT(*) AS trackCount FROM tracks
           WHERE inLibrary = 1 AND album IS NOT NULL AND album != ''
           GROUP BY album, artist ORDER BY album COLLATE NOCASE"""
    )
    fun observeAlbums(): Flow<List<AlbumSummary>>

    @Query(
        """SELECT artist, MAX(artworkUrl) AS artworkUrl, COUNT(*) AS trackCount FROM tracks
           WHERE inLibrary = 1 GROUP BY artist ORDER BY artist COLLATE NOCASE"""
    )
    fun observeArtists(): Flow<List<ArtistSummary>>

    @Query("SELECT * FROM tracks WHERE album = :album AND artist = :artist ORDER BY addedAt")
    fun observeAlbum(album: String, artist: String): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE artist = :artist AND (inLibrary = 1 OR lastPlayedAt IS NOT NULL) ORDER BY playCount DESC, title COLLATE NOCASE")
    fun observeArtist(artist: String): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE inLibrary = 1 AND (title LIKE '%' || :q || '%' OR artist LIKE '%' || :q || '%' OR album LIKE '%' || :q || '%') LIMIT 30")
    suspend fun searchLibrary(q: String): List<Track>
}

@Dao
interface PlaylistDao {
    @Insert
    suspend fun insert(playlist: Playlist): Long

    @Update
    suspend fun update(playlist: Playlist)

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): Playlist?

    @Query("UPDATE playlists SET coverUrl = :cover, updatedAt = :now WHERE id = :id")
    suspend fun setCover(id: Long, cover: String?, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: Long): Flow<Playlist?>

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :id")
    suspend fun clearTracks(id: Long)

    @Query("DELETE FROM unmatched WHERE playlistId = :id")
    suspend fun clearUnmatched(id: Long)

    @Transaction
    suspend fun deleteFully(id: Long) {
        clearTracks(id)
        clearUnmatched(id)
        delete(id)
    }

    @Query("UPDATE playlists SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long = System.currentTimeMillis())

    /** a playlist found on spotify: its real name, cover and id */
    @Query("UPDATE playlists SET name = :name, coverUrl = :cover, originId = :spotifyId, updatedAt = :now WHERE id = :id")
    suspend fun setSpotify(id: Long, name: String, cover: String, spotifyId: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE playlists SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long = System.currentTimeMillis())

    @Query(
        """SELECT p.id, p.name, p.description, p.coverUrl, p.origin, p.originId, p.updatedAt,
           (SELECT COUNT(*) FROM playlist_tracks pt WHERE pt.playlistId = p.id) AS trackCount,
           (SELECT COUNT(*) FROM playlist_tracks pt JOIN tracks t ON t.id = pt.trackId
              WHERE pt.playlistId = p.id AND t.downloadState = 'DONE') AS downloadedCount,
           (SELECT t.artworkUrl FROM playlist_tracks pt JOIN tracks t ON t.id = pt.trackId
              WHERE pt.playlistId = p.id ORDER BY pt.position LIMIT 1) AS firstArtwork
           FROM playlists p ORDER BY p.updatedAt DESC"""
    )
    fun observeAll(): Flow<List<PlaylistWithCount>>

    @Query("SELECT t.*, pt.position AS position FROM tracks t JOIN playlist_tracks pt ON pt.trackId = t.id WHERE pt.playlistId = :id ORDER BY pt.position")
    fun observeEntries(id: Long): Flow<List<PlaylistEntry>>

    @Query("SELECT t.* FROM tracks t JOIN playlist_tracks pt ON pt.trackId = t.id WHERE pt.playlistId = :id ORDER BY pt.position")
    suspend fun tracks(id: Long): List<Track>

    /** after the last song, counting not-found songs too so their spots stay free */
    @Query(
        """SELECT COALESCE(MAX(p), -1) + 1 FROM (
             SELECT position AS p FROM playlist_tracks WHERE playlistId = :id
             UNION ALL SELECT position AS p FROM unmatched WHERE playlistId = :id)"""
    )
    suspend fun nextPosition(id: Long): Int

    /** returns -1 when that spot was already taken */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrack(ref: PlaylistTrack): Long

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId)")
    fun observeContains(playlistId: Long, trackId: String): Flow<Boolean>

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrack(playlistId: Long, trackId: String)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND position = :position")
    suspend fun removeAt(playlistId: Long, position: Int)

    @Insert
    suspend fun insertUnmatched(track: UnmatchedTrack): Long

    @Query("SELECT * FROM unmatched WHERE playlistId = :id ORDER BY position")
    fun observeUnmatched(id: Long): Flow<List<UnmatchedTrack>>

    @Query("SELECT * FROM unmatched WHERE playlistId = :id ORDER BY position")
    suspend fun unmatched(id: Long): List<UnmatchedTrack>

    @Query("SELECT * FROM unmatched ORDER BY playlistId, position")
    suspend fun allUnmatched(): List<UnmatchedTrack>

    /** every not-found song, in every playlist and liked songs (the rescan screen's list) */
    @Query("SELECT * FROM unmatched ORDER BY playlistId, position")
    fun observeAllUnmatched(): Flow<List<UnmatchedTrack>>

    @Query("SELECT COUNT(*) FROM unmatched")
    fun observeUnmatchedCount(): Flow<Int>

    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    suspend fun all(): List<Playlist>

    @Query("SELECT t.*, pt.position AS position FROM tracks t JOIN playlist_tracks pt ON pt.trackId = t.id WHERE pt.playlistId = :id ORDER BY pt.position")
    suspend fun entries(id: Long): List<PlaylistEntry>

    /** the artist that shows up most across all playlists */
    @Query("SELECT t.artist FROM playlist_tracks pt JOIN tracks t ON t.id = pt.trackId WHERE t.artist != '' GROUP BY t.artist ORDER BY COUNT(*) DESC LIMIT 1")
    suspend fun topArtist(): String?

    @Query("SELECT * FROM playlists WHERE origin = 'mix' AND originId = :mixId LIMIT 1")
    suspend fun findMix(mixId: String): Playlist?

    @Query("SELECT * FROM playlists WHERE origin = 'mix'")
    suspend fun mixes(): List<Playlist>

    @Query("SELECT * FROM playlists WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): Playlist?

    @Query("SELECT * FROM unmatched WHERE id = :id")
    suspend fun getUnmatched(id: Long): UnmatchedTrack?

    @Query("DELETE FROM unmatched WHERE id = :id")
    suspend fun deleteUnmatched(id: Long)
}

@Dao
interface ImportDao {
    @Insert
    suspend fun insertJob(job: ImportJob): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<ImportJobTrack>)

    @Query("SELECT * FROM import_jobs ORDER BY id")
    fun observeJobs(): Flow<List<ImportJob>>

    @Query("SELECT * FROM import_jobs WHERE state IN ('queued', 'running') ORDER BY state = 'running' DESC, id LIMIT 1")
    suspend fun nextJob(): ImportJob?

    @Query("SELECT state FROM import_jobs WHERE id = :id")
    suspend fun state(id: Long): String?

    @Query("SELECT COUNT(*) FROM import_jobs WHERE state IN ('queued', 'running')")
    suspend fun pendingCount(): Int

    @Query("SELECT * FROM import_job_tracks WHERE jobId = :jobId AND state = 'pending' ORDER BY position")
    suspend fun pendingTracks(jobId: Long): List<ImportJobTrack>

    @Query("UPDATE import_job_tracks SET state = :state WHERE jobId = :jobId AND position = :position")
    suspend fun setTrackState(jobId: Long, position: Int, state: String)

    @Query("UPDATE import_jobs SET state = :state WHERE id = :id")
    suspend fun setState(id: Long, state: String)

    @Query("UPDATE import_jobs SET playlistId = :playlistId WHERE id = :id")
    suspend fun setPlaylist(id: Long, playlistId: Long)

    @Query("UPDATE import_jobs SET done = done + 1, matched = matched + :matched WHERE id = :id")
    suspend fun countTrack(id: Long, matched: Int)

    @Query("UPDATE import_jobs SET state = 'cancelled' WHERE state IN ('queued', 'running')")
    suspend fun cancelAll()

    @Query("UPDATE import_jobs SET state = 'cancelled' WHERE id = :id AND state IN ('queued', 'running')")
    suspend fun cancel(id: Long)

    @Query("DELETE FROM import_job_tracks WHERE jobId IN (SELECT id FROM import_jobs WHERE state IN ('done', 'cancelled'))")
    suspend fun clearFinishedTracks()

    @Query("DELETE FROM import_jobs WHERE state IN ('done', 'cancelled')")
    suspend fun clearFinishedJobs()

    @Transaction
    suspend fun clearFinished() {
        clearFinishedTracks()
        clearFinishedJobs()
    }

    /**
     * imported songs and the spotify cover they should have, found through the exact playlist
     * spot they were put in. for fixing imports from before covers were applied properly.
     */
    @Query(
        """SELECT pt.trackId AS trackId, jt.artworkUrl AS artworkUrl, jt.sourceUri AS sourceUri, jt.album AS album
           FROM import_job_tracks jt
           JOIN import_jobs j ON j.id = jt.jobId
           JOIN playlist_tracks pt ON pt.playlistId = j.playlistId AND pt.position = jt.position
           JOIN tracks t ON t.id = pt.trackId
           WHERE jt.state = 'matched' AND j.intoLiked = 0
             AND (jt.artworkUrl IS NOT NULL OR jt.sourceUri LIKE 'spotify:track:%')
             AND (t.artworkUrl IS NULL OR t.artworkUrl != COALESCE(jt.artworkUrl, ''))"""
    )
    suspend fun coverRepairs(): List<CoverRepair>

    /** matched songs from imported liked songs, which have no playlist spot to look them up by */
    @Query(
        """SELECT jt.* FROM import_job_tracks jt JOIN import_jobs j ON j.id = jt.jobId
           WHERE j.intoLiked = 1 AND jt.state = 'matched' AND (jt.artworkUrl IS NOT NULL OR jt.sourceUri LIKE 'spotify:track:%')"""
    )
    suspend fun likedImports(): List<ImportJobTrack>

    /** songs the current imports couldn't find, newest first */
    @Query(
        """SELECT * FROM unmatched WHERE playlistId IN (SELECT playlistId FROM import_jobs WHERE playlistId IS NOT NULL)
           OR (playlistId = -1 AND EXISTS (SELECT 1 FROM import_jobs WHERE intoLiked = 1))
           ORDER BY id DESC"""
    )
    fun observeMissing(): Flow<List<UnmatchedTrack>>

    /**
     * saved songs still wearing a youtube video thumbnail (or nothing) that the cover fixer
     * hasn't tried yet. youtube music song art (googleusercontent) is already the real cover.
     */
    @Query(
        """SELECT * FROM tracks WHERE (artworkUrl IS NULL OR artworkUrl LIKE '%ytimg.com%')
           AND (inLibrary = 1 OR liked = 1 OR id IN (SELECT trackId FROM playlist_tracks))
           AND id NOT IN (SELECT trackId FROM cover_checks) LIMIT :limit"""
    )
    suspend fun needingCovers(limit: Int = 50): List<Track>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun coverChecked(check: CoverCheck)

    @Query("SELECT * FROM artist_genres WHERE artist = :artist")
    suspend fun genre(artist: String): ArtistGenre?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveGenre(g: ArtistGenre)

    @Query("SELECT * FROM match_cache WHERE `key` = :key")
    suspend fun cached(key: String): MatchCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun cache(entry: MatchCache)

    /** forget "not found" answers so a rescan really searches again */
    @Query("DELETE FROM match_cache WHERE trackId IS NULL")
    suspend fun forgetMisses()
}
