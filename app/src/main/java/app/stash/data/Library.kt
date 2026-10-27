package app.stash.data

import app.stash.data.db.LIKED_PLAYLIST_ID
import app.stash.data.db.Playlist
import app.stash.data.db.PlaylistDao
import app.stash.data.db.PlaylistTrack
import app.stash.data.db.Track
import app.stash.data.db.TrackDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** fire-and-forget library edits the ui calls from click handlers */
@Singleton
class Library @Inject constructor(
    val tracks: TrackDao,
    val playlists: PlaylistDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun setLiked(track: Track, liked: Boolean) = scope.launch {
        tracks.ensure(track)
        tracks.setLiked(track.id, liked)
    }

    fun save(track: Track) = scope.launch {
        tracks.ensure(track)
        tracks.addToLibrary(track.id)
    }

    fun removeFromLibrary(track: Track) = scope.launch { tracks.removeFromLibrary(track.id) }

    suspend fun createPlaylist(name: String): Long =
        playlists.insert(Playlist(name = name.trim().ifEmpty { "New playlist" }))

    fun addToPlaylist(playlistId: Long, items: List<Track>) = scope.launch {
        var pos = playlists.nextPosition(playlistId)
        for (t in items) {
            val saved = tracks.ensure(t)
            tracks.addToLibrary(saved.id)
            playlists.insertTrack(PlaylistTrack(playlistId, saved.id, pos++))
        }
        playlists.touch(playlistId)
    }

    fun createPlaylistWith(name: String, items: List<Track>) = scope.launch {
        val id = createPlaylist(name)
        addToPlaylist(id, items).join()
    }

    /** takes every copy of [track] out of the playlist */
    fun removeTrackFromPlaylist(playlistId: Long, track: Track) = scope.launch {
        playlists.removeTrack(playlistId, track.id)
        playlists.touch(playlistId)
    }

    fun removeFromPlaylist(playlistId: Long, position: Int) = scope.launch {
        playlists.removeAt(playlistId, position)
        playlists.touch(playlistId)
    }

    fun rename(playlistId: Long, name: String) = scope.launch { playlists.rename(playlistId, name) }

    /** a picked photo (file uri) or a spotify cover url; null goes back to the first song's cover */
    fun setPlaylistCover(playlistId: Long, cover: String?) = scope.launch { playlists.setCover(playlistId, cover) }

    fun deletePlaylist(playlistId: Long) = scope.launch { playlists.deleteFully(playlistId) }

    /** a hand-picked replacement for a song the importer couldn't match */
    fun resolveUnmatched(unmatchedId: Long, pick: Track) = scope.launch {
        val u = playlists.getUnmatched(unmatchedId) ?: return@launch
        val saved = tracks.ensure(pick)
        tracks.addToLibrary(saved.id)
        if (u.playlistId == LIKED_PLAYLIST_ID) {
            if (u.likedAt > 0) tracks.importLike(saved.id, u.likedAt) else tracks.setLiked(saved.id, true)
        } else {
            // same slot the song had on spotify, so the order stays right
            if (playlists.insertTrack(PlaylistTrack(u.playlistId, saved.id, u.position)) == -1L) {
                playlists.insertTrack(PlaylistTrack(u.playlistId, saved.id, playlists.nextPosition(u.playlistId)))
            }
            playlists.touch(u.playlistId)
        }
        playlists.deleteUnmatched(unmatchedId)
    }

    fun dismissUnmatched(unmatchedId: Long) = scope.launch { playlists.deleteUnmatched(unmatchedId) }
}
