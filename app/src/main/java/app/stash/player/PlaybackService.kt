package app.stash.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.stash.MainActivity
import app.stash.R
import app.stash.data.db.TrackDao
import app.stash.data.prefs.Settings
import app.stash.player.PlayerConnection.Companion.toMediaItem
import app.stash.providers.Resolver
import app.stash.recs.Recommender
import kotlinx.coroutines.withContext
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject

/**
 * Background playback. The queue holds "stash://track/<id>" uris; the real audio url is only
 * looked up (downloaded file, else a fresh stream url) when exoplayer opens the item, so
 * expiring yt-dlp urls never sit in the queue.
 */
@AndroidEntryPoint
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    @Inject lateinit var resolver: Resolver
    @Inject lateinit var dao: TrackDao
    @Inject lateinit var http: OkHttpClient
    @Inject lateinit var recommender: Recommender
    @Inject lateinit var settings: Settings
    @Inject lateinit var web: WebViewPlayer

    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        val upstream = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(http))
        val resolving = ResolvingDataSource.Factory(upstream) { spec -> resolve(spec) }

        // start playing after half a second of audio instead of the default 2.5s; it keeps
        // streaming (and buffering ahead) while it plays, nothing waits for the whole file
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 500, 1_500)
            .build()
        val player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var switchedAt = 0L
        var waitingForSound = false
        // songs already given their one slow retry / their one hls switch
        val retried = mutableSetOf<String>()
        val hlsSwitched = mutableSetOf<String>()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId ?: return
                // moved on from a song the webview was playing (or being asked about)
                if (web.active.value?.trackId?.let { it != id } == true) web.stop()
                if (web.offer.value?.let { it != id } == true) web.dismiss()
                scope.launch { dao.markPlayed(id) }
                // look up the next song's stream now, so the switch doesn't wait on yt-dlp
                // get the next two songs ready while this one plays: on connections where youtube
                // only works through yt-dlp, that's the difference between a gap and none
                val index = player.currentMediaItemIndex
                val upcoming = (1..2).map { (index + it) }.filter { it < player.mediaItemCount }
                    .map { player.getMediaItemAt(it).mediaId }
                scope.launch { upcoming.forEach { id -> runCatching { resolver.streamFor(id, ahead = true) } } }
                switchedAt = System.currentTimeMillis()
                waitingForSound = true
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // the real number: from switching to a song until it's audible
                if (isPlaying && waitingForSound) {
                    waitingForSound = false
                    resolver.recordPlaying(
                        player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "?",
                        System.currentTimeMillis() - switchedAt,
                    )
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) autoplay(player)
            }

            override fun onPlayerError(error: PlaybackException) {
                val id = player.currentMediaItem?.mediaId
                // what actually went wrong, down to the root cause, for settings → playback log
                val root = generateSequence(error as Throwable) { it.cause }.last()
                resolver.recordPlayerError(
                    player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "?",
                    "${error.errorCodeName}: ${root.javaClass.simpleName} ${root.message.orEmpty()}".take(300),
                )
                val item = player.currentMediaItem
                // the stream came back hls when the queue expected a plain file (a song that fell
                // back to soundcloud), or the other way round: reopen it the right way
                if (id != null && item != null && error.errorCode in FORMAT_ERRORS && hlsSwitched.add(id)) {
                    val wasHls = item.mediaMetadata.extras?.getBoolean(EXTRA_HLS) == true
                    val hls = resolver.cachedHls(id) ?: !wasHls
                    if (hls != wasHls) {
                        val index = player.currentMediaItemIndex
                        val position = player.currentPosition
                        val extras = android.os.Bundle(item.mediaMetadata.extras ?: android.os.Bundle()).apply { putBoolean(EXTRA_HLS, hls) }
                        player.replaceMediaItem(
                            index,
                            item.buildUpon().setUri(uriFor(id, hls))
                                .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build(),
                        )
                        player.seekTo(index, position)
                        player.prepare()
                        player.play()
                        return
                    }
                }
                // first failure of a song: youtube's fast link broke (it does that to some
                // videos). get it again the slow sure way and carry on from the same spot
                if (id != null && retried.add(id)) {
                    val index = player.currentMediaItemIndex
                    val position = player.currentPosition
                    scope.launch {
                        resolver.retrySlow(id)
                    }.invokeOnCompletion {
                        // the player only takes calls on the main thread
                        mainHandler.post {
                            player.seekTo(index, position)
                            player.prepare()
                            player.play()
                        }
                    }
                    return
                }
                // failed the sure way too: forget it, then offer the last resort (music.youtube.com in
                // a hidden webview); a no, or no answer, moves on instead of stopping the whole queue
                id?.let(resolver::invalidate)
                if (id == null) return skip(player)
                player.pause()
                if (web.active.value?.trackId == id) return
                val index = player.currentMediaItemIndex
                val meta = player.currentMediaItem?.mediaMetadata
                val query = "${meta?.artist ?: ""} ${meta?.title ?: ""}".trim()
                web.offer(
                    id,
                    accept = { tid ->
                        scope.launch {
                            val videoId = resolver.youtubeIdFor(tid)
                            web.start(tid, videoId, query) {
                                // the webview finished the song: carry on with the queue
                                mainHandler.post { if (player.currentMediaItemIndex == index) skip(player) }
                            }
                        }
                    },
                    decline = { mainHandler.post { if (player.currentMediaItemIndex == index) skip(player) } },
                )
            }

            private fun skip(player: Player) {
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        })

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_stat_stash) }
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                // controllers strip uris off media items, so rebuild them from the id
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> =
                    Futures.immediateFuture(mediaItems.map { it.withStashUri() }.toMutableList())
            })
            .build()
    }

    /** queue ran out: carry on with songs similar to the last one, like spotify's autoplay */
    private fun autoplay(player: ExoPlayer) {
        val last = player.currentMediaItem?.mediaId ?: return
        val inQueue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
        scope.launch {
            if (!settings.current().autoplay) return@launch
            val seed = dao.get(last) ?: return@launch
            val next = runCatching { recommender.radioFor(seed) }.getOrDefault(emptyList())
                .filter { it.id !in inQueue }
                .take(20)
            if (next.isEmpty()) return@launch
            next.forEach { dao.ensure(it) }
            withContext(Dispatchers.Main) {
                val start = player.mediaItemCount
                player.addMediaItems(next.map { it.toMediaItem() })
                player.seekTo(start, 0)
                player.prepare()
                player.play()
            }
        }
    }

    private fun resolve(spec: DataSpec): DataSpec {
        val uri = spec.uri
        if (uri.scheme != SCHEME) return spec
        val trackId = Uri.decode(uri.lastPathSegment?.removeSuffix(HLS_SUFFIX) ?: return spec)
        val source = try {
            runBlocking { resolver.streamFor(trackId) }
        } catch (e: Exception) {
            throw IOException("couldn't play: ${e.message}", e)
        }
        return spec.buildUpon()
            .setUri(Uri.parse(source.url))
            .setHttpRequestHeaders(spec.httpRequestHeaders + source.headers)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        web.stop()
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val SCHEME = "stash"
        const val HLS_SUFFIX = ".m3u8"
        private val FORMAT_ERRORS = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        )
        const val EXTRA_HLS = "hls"

        /**
         * [hls] streams get a .m3u8 ending so exoplayer picks its hls loader up front, before
         * the real (soundcloud) playlist url has been looked up
         */
        fun uriFor(trackId: String, hls: Boolean = false): Uri =
            Uri.parse("$SCHEME://track/${Uri.encode(trackId)}${if (hls) HLS_SUFFIX else ""}")

        fun MediaItem.withStashUri(): MediaItem =
            buildUpon().setUri(uriFor(mediaId, mediaMetadata.extras?.getBoolean(EXTRA_HLS) == true)).build()
    }
}
