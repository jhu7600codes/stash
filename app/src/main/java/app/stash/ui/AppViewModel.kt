package app.stash.ui

import app.stash.data.calm
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stash.data.Library
import app.stash.data.db.PlaylistWithCount
import app.stash.data.db.Track
import app.stash.data.prefs.AppSettings
import app.stash.data.prefs.Settings
import app.stash.download.Downloads
import app.stash.importer.ImportQueue
import app.stash.importer.RescanProgress
import app.stash.player.PlayerConnection
import app.stash.recs.Recommender
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * app-wide actions every screen needs (play, queue, download, like, add to playlist),
 * handed down through [LocalActions] instead of being re-injected into every screen.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    val player: PlayerConnection,
    val downloads: Downloads,
    val library: Library,
    private val recommender: Recommender,
    private val imports: ImportQueue,
    val prefs: Settings,
    val lyrics: app.stash.recs.Lyrics,
    private val resolver: app.stash.providers.Resolver,
    val pairing: app.stash.cloud.Pairing,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = prefs.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val playlists: StateFlow<List<PlaylistWithCount>> =
        library.playlists.observeAll().calm().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    fun toast(msg: String) {
        _messages.tryEmit(msg)
    }

    init {
        player.connect()
    }

    fun play(tracks: List<Track>, index: Int = 0) = player.play(tracks, index)
    fun shuffle(tracks: List<Track>) = player.play(tracks, shuffle = true)

    /** plays the song, then youtube music's radio for it */
    fun startRadio(track: Track) {
        toast("Starting radio")
        val started = player.play(listOf(track))
        viewModelScope.launch {
            val similar = runCatching { recommender.radioFor(track) }.getOrDefault(emptyList())
            started?.join() // the song itself has to be queued before the radio goes after it
            if (similar.isEmpty()) toast("Couldn't find similar songs") else player.addToQueue(similar)
        }
    }

    /** every song that couldn't be found anywhere, across all playlists and liked songs */
    val missingCount: StateFlow<Int> =
        library.playlists.observeUnmatchedCount().calm().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val rescan: StateFlow<RescanProgress?> = imports.rescan.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun rescanMissing(restart: Boolean = false) {
        imports.rescanMissing(restart)
        toast(if (restart) "Rescan started over" else "Searching for missing songs again in the background")
    }

    fun stopRescan() {
        imports.stopRescan()
        toast("Rescan stopped")
    }

    fun playNext(track: Track) {
        player.playNext(track); toast("Playing next")
    }

    fun addToQueue(tracks: List<Track>) {
        player.addToQueue(tracks); toast(if (tracks.size == 1) "Added to queue" else "${tracks.size} added to queue")
    }

    fun download(tracks: List<Track>) {
        downloads.enqueue(tracks)
        toast(if (tracks.size == 1) "Downloading" else "Downloading ${tracks.size} songs")
    }

    fun removeDownload(track: Track) {
        downloads.delete(track); toast("Removed download")
    }

    fun setLiked(track: Track, liked: Boolean) = library.setLiked(track, liked)

    /** "wrong song": from now on [trackId] plays [right] (picked by hand in search) */
    fun rematch(trackId: String, right: Track) {
        viewModelScope.launch {
            library.tracks.setResolved(trackId, right.provider, right.sourceId, right.streamHint)
            resolver.invalidate(trackId)
            toast("Fixed: plays ${right.title} now")
        }
    }

    /** the playlist the heart button fills, null = liked songs (settings, or long-press a heart) */
    val heartTarget: StateFlow<PlaylistWithCount?> = kotlinx.coroutines.flow.combine(playlists, settings) { all, s ->
        s?.heartPlaylist?.let { id -> all.firstOrNull { it.id == id } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** is the heart filled for [trackId]: liked, or in the heart's playlist */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun hearted(trackId: String): kotlinx.coroutines.flow.Flow<Boolean> =
        heartTarget.map { it?.id }.distinctUntilChanged().flatMapLatest { id ->
            if (id == null) library.tracks.observe(trackId).map { it?.liked == true }
            else library.playlists.observeContains(id, trackId)
        }.distinctUntilChanged()

    fun toggleHeart(track: Track, hearted: Boolean) {
        val target = heartTarget.value
        when {
            target == null -> setLiked(track, !hearted)
            hearted -> { library.removeTrackFromPlaylist(target.id, track); toast("Removed from ${target.name}") }
            else -> { library.addToPlaylist(target.id, listOf(track)); toast("Added to ${target.name}") }
        }
    }

    fun setHeartTarget(playlist: PlaylistWithCount?) {
        viewModelScope.launch { prefs.setHeartPlaylist(playlist?.id) }
        toast("The heart now adds to ${playlist?.name ?: "Liked songs"}")
    }

    /** youtube music's radio for a song, for the player's "similar songs" */
    suspend fun similarTo(track: Track): List<Track> = recommender.radioFor(track)

    fun setLyricsTranslate(on: Boolean) {
        viewModelScope.launch { prefs.setLyricsTranslate(on) }
    }

    /** pinned playlists, in pin order (only ones that still exist) */
    val pinned: StateFlow<List<PlaylistWithCount>> = kotlinx.coroutines.flow.combine(playlists, settings) { all, s ->
        s?.pinnedPlaylists.orEmpty().mapNotNull { id -> all.firstOrNull { it.id == id } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** the library's playlists: made-for-you mixes only once saved */
    val libraryPlaylists: StateFlow<List<PlaylistWithCount>> = kotlinx.coroutines.flow.combine(playlists, settings) { all, s ->
        val saved = s?.savedMixes.orEmpty()
        all.filter { it.origin != "mix" || it.originId in saved }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setMixSaved(mixId: String, saved: Boolean) {
        viewModelScope.launch { prefs.setMixSaved(mixId, saved) }
        toast(if (saved) "Saved to your library" else "Removed from your library")
    }

    fun isPinned(id: Long) = settings.value?.pinnedPlaylists?.contains(id) == true

    fun setPinned(id: Long, pinned: Boolean) {
        viewModelScope.launch { prefs.setPinned(id, pinned) }
        toast(if (pinned) "Pinned to home" else "Unpinned")
    }

    fun addToPlaylist(playlistId: Long, name: String, tracks: List<Track>) {
        library.addToPlaylist(playlistId, tracks); toast("Added to $name")
    }

    fun newPlaylistWith(name: String, tracks: List<Track>) {
        viewModelScope.launch {
            library.createPlaylistWith(name, tracks).join()
            toast("Created $name")
        }
    }
}

val LocalActions = staticCompositionLocalOf<AppViewModel> { error("No AppViewModel") }
