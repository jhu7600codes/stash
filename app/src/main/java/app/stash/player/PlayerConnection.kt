package app.stash.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.stash.data.db.Track
import app.stash.data.db.TrackDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class PlayerUiState(
    val currentId: String? = null,
    val title: String = "",
    val artist: String = "",
    val artworkUri: Uri? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<MediaItem> = emptyList(),
    val queueIndex: Int = 0,
    val error: String? = null,
    /** playing through the hidden webview (music.youtube.com) */
    val viaWeb: Boolean = false,
)

/** the ui's handle on [PlaybackService], through a media3 controller */
@Singleton
class PlayerConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: TrackDao,
    val web: WebViewPlayer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    // lists only care which song is on and whether it's playing; watching these instead of the
    // whole state keeps them from redrawing on every buffering / position event
    private val _currentId = MutableStateFlow<String?>(null)
    val currentId: StateFlow<String?> = _currentId
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    private var queueCache: List<MediaItem> = emptyList()

    init {
        scope.launch { web.active.collect { refresh(queueChanged = false) } }
    }

    /** the webview's playback, when it's the song on now */
    private fun webNow() = web.active.value?.takeIf { it.trackId == controller?.currentMediaItem?.mediaId }

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) =
                    refresh(queueChanged = events.contains(Player.EVENT_TIMELINE_CHANGED))
            })
            refresh(queueChanged = true)
            pending.forEach { it(c) }
            pending.clear()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else {
            pending += block
            connect()
        }
    }

    private fun refresh(queueChanged: Boolean) {
        val c = controller ?: return
        val item = c.currentMediaItem
        // copying a long queue on every event is wasteful; only when it actually changed
        if (queueChanged) queueCache = (0 until c.mediaItemCount).map { c.getMediaItemAt(it) }
        val queue = queueCache
        val w = webNow()
        _currentId.value = item?.mediaId
        _isPlaying.value = w?.playing ?: c.isPlaying
        _state.value = PlayerUiState(
            currentId = item?.mediaId,
            title = item?.mediaMetadata?.title?.toString() ?: "",
            artist = item?.mediaMetadata?.artist?.toString() ?: "",
            artworkUri = item?.mediaMetadata?.artworkUri,
            isPlaying = w?.playing ?: c.isPlaying,
            isBuffering = w?.loading ?: (c.playbackState == Player.STATE_BUFFERING),
            durationMs = w?.durationMs ?: (c.duration.takeIf { it > 0 } ?: 0),
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            queue = queue,
            queueIndex = c.currentMediaItemIndex,
            error = if (w != null) null else c.playerError?.let { it.cause?.message ?: it.message },
            viaWeb = w != null,
        )
    }

    val positionMs: Long get() = webNow()?.positionMs ?: controller?.currentPosition ?: 0
    val bufferedMs: Long get() = controller?.bufferedPosition ?: 0

    private suspend fun save(tracks: List<Track>) = withContext(Dispatchers.IO) {
        // the service resolves by id, so every queued track has to exist in the db
        dao.insertAllIgnore(tracks)
    }

    /** the returned job finishes once the queue has been handed to the player */
    fun play(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false): Job? {
        if (tracks.isEmpty()) return null
        return scope.launch {
            save(tracks)
            withController { c ->
                c.shuffleModeEnabled = shuffle
                val start = if (shuffle) tracks.indices.random() else startIndex.coerceIn(tracks.indices)
                c.setMediaItems(tracks.map { it.toMediaItem() }, start, 0)
                c.prepare()
                c.play()
            }
        }
    }

    fun playNext(track: Track) = scope.launch {
        save(listOf(track))
        withController { c ->
            if (c.mediaItemCount == 0) {
                c.setMediaItem(track.toMediaItem()); c.prepare(); c.play()
            } else c.addMediaItem(c.currentMediaItemIndex + 1, track.toMediaItem())
        }
    }

    fun addToQueue(tracks: List<Track>) = scope.launch {
        save(tracks)
        withController { c ->
            val empty = c.mediaItemCount == 0
            c.addMediaItems(tracks.map { it.toMediaItem() })
            if (empty) { c.prepare(); c.play() }
        }
    }

    fun togglePlay() {
        // a song the webview is playing: play / pause it there
        webNow()?.let { if (it.playing) web.pause() else web.play(); return }
        withController { c ->
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.isPlaying) c.pause() else c.play()
        }
    }

    fun next() = withController { it.seekToNext() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) {
        if (webNow() != null) web.seekTo(ms) else withController { it.seekTo(ms) }
    }
    fun skipTo(index: Int) = withController { it.seekTo(index, 0); it.play() }
    fun removeAt(index: Int) = withController { it.removeMediaItem(index) }
    fun move(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun clearQueue() = withController { it.stop(); it.clearMediaItems() }

    companion object {
        /**
         * soundcloud streams are hls, so the player has to open them as hls: soundcloud songs,
         * and songs from elsewhere that fell back to soundcloud. downloads are plain files. if
         * this guess is wrong for a song the player switches when it fails (PlaybackService).
         */
        private val Track.isHls get() = !isDownloaded &&
            (resolvedProvider == "soundcloud" || (provider == "soundcloud" && resolvedProvider == null))

        fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
            .setMediaId(id)
            .setUri(PlaybackService.uriFor(id, isHls))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(artworkUrl?.let(Uri::parse))
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setExtras(Bundle().apply {
                        putLong("durationMs", durationMs)
                        putBoolean(PlaybackService.EXTRA_HLS, isHls)
                    })
                    .build()
            )
            .build()
    }
}
