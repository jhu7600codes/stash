package app.stash.importer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.stash.MainActivity
import app.stash.R
import app.stash.StashApp
import app.stash.data.db.ImportDao
import app.stash.data.db.ImportJob
import app.stash.data.db.ImportJobTrack
import app.stash.data.db.LIKED_PLAYLIST_ID
import app.stash.data.db.MatchCache
import app.stash.data.db.Playlist
import app.stash.data.db.PlaylistDao
import app.stash.data.db.PlaylistTrack
import app.stash.data.db.StashDatabase
import app.stash.data.db.Track
import app.stash.data.db.TrackDao
import app.stash.data.db.UnmatchedTrack
import app.stash.providers.Matcher
import app.stash.providers.Resolver
import app.stash.recs.Covers
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/** works through [ImportQueue] job by job, song by song, saving after every song */
@HiltWorker
class ImportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val db: StashDatabase,
    private val dao: ImportDao,
    private val tracks: TrackDao,
    private val playlists: PlaylistDao,
    private val resolver: Resolver,
    private val covers: Covers,
    private val playlistCovers: PlaylistCovers,
) : CoroutineWorker(context, params) {

    private var lastNotified = 0L
    /** newest last; shown in the expanded notification */
    private val missing = java.util.Collections.synchronizedList(mutableListOf<String>())

    override suspend fun doWork(): Result {
        runCatching { setForeground(notification("Getting ready", null, 0, 0)) }
        while (!isStopped) {
            val job = dao.nextJob() ?: break
            runJob(job)
        }
        covers.fixInBackground()
        return Result.success()
    }

    private suspend fun runJob(job: ImportJob) {
        dao.setState(job.id, "running")
        val playlistId = when {
            job.intoLiked -> LIKED_PLAYLIST_ID
            job.playlistId != null -> job.playlistId
            else -> playlists.insert(
                Playlist(name = job.name, coverUrl = job.coverUrl, origin = job.origin, originId = job.originId)
            ).also { dao.setPlaylist(job.id, it) }
        }
        // an exportify file only has the name; if that playlist's spotify id was seen before
        // (profile / exportify cover grab), its real cover comes along
        if (!job.intoLiked && job.origin == "spotify" && job.coverUrl == null) {
            playlistCovers.knownId(job.name)?.let { spotifyId ->
                runCatching { playlistCovers.oembed(spotifyId) }.getOrNull()?.let { (name, cover) ->
                    if (cover != null) playlists.setSpotify(playlistId, name ?: job.name, cover, spotifyId)
                }
            }
        }
        val queuePos = dao.pendingCount()
        val done = AtomicInteger(job.done)
        val gate = Semaphore(PARALLEL)

        coroutineScope {
            dao.pendingTracks(job.id).map { t ->
                async {
                    gate.withPermit {
                        if (dao.state(job.id) == "cancelled") return@withPermit
                        val hit = match(t, if (job.intoLiked) null else job.name)
                        save(job, playlistId, t, hit)
                        notify(job, done.incrementAndGet(), queuePos, t)
                    }
                }
            }.awaitAll()
        }
        // cancelled from the ui while running: leave it cancelled
        if (dao.state(job.id) == "running") dao.setState(job.id, "done")
        if (!job.intoLiked) playlists.touch(playlistId)
    }

    private suspend fun save(job: ImportJob, playlistId: Long, t: ImportJobTrack, hit: Track?) = db.withTransaction {
        // spotify's liked songs come newest first: keep that order in stash's liked songs
        val likedAt = job.createdAt - t.position * 1000L
        if (hit != null) {
            if (job.intoLiked) tracks.importLike(hit.id, likedAt)
            else {
                tracks.addToLibrary(hit.id)
                playlists.insertTrack(PlaylistTrack(playlistId, hit.id, t.position))
            }
        } else {
            missing += "${t.artist} - ${t.title}"
            playlists.insertUnmatched(
                UnmatchedTrack(
                    playlistId = playlistId, title = t.title, artist = t.artist, album = t.album,
                    durationMs = t.durationMs, position = t.position, likedAt = if (job.intoLiked) likedAt else 0,
                )
            )
        }
        dao.setTrackState(t.jobId, t.position, if (hit != null) "matched" else "unmatched")
        dao.countTrack(t.jobId, if (hit != null) 1 else 0)
    }

    /** the same song in several playlists is only searched once */
    private suspend fun match(t: ImportJobTrack, context: String?): Track? {
        val key = Matcher.normalize(t.artist.split(',').first()) + "|" + Matcher.normalize(t.title)
        val cached = dao.cached(key)
        if (cached != null) {
            // misses from an older, worse matcher are always retried
            val fresh = System.currentTimeMillis() - cached.checkedAt < RETRY_MISSES_AFTER && cached.checkedAt > MATCHER_CHANGED_AT
            if (cached.trackId != null) tracks.get(cached.trackId)?.let { return withSpotifyCover(it, t) }
            else if (fresh) return null
        }
        val hit = runCatching { resolver.match(t.title, t.artist, t.durationMs, t.album, context) }.getOrNull()
        val saved = hit?.let {
            // keep the nicer metadata from spotify / the csv
            tracks.ensure(
                it.copy(
                    title = t.title,
                    artist = t.artist,
                    album = t.album ?: it.album,
                    durationMs = if (t.durationMs > 0) t.durationMs else it.durationMs,
                )
            )
        }?.let { withSpotifyCover(it, t) }
        dao.cache(MatchCache(key, saved?.id))
        return saved
    }

    /**
     * the spotify cover beats whatever thumbnail the matched source has. set it even when the
     * song was already saved (from a search, a mix, another playlist): ensure() alone keeps the
     * old row, which is how imported songs used to end up with youtube thumbnails.
     */
    private suspend fun withSpotifyCover(track: Track, t: ImportJobTrack): Track {
        val art = t.artworkUrl ?: covers.spotify(t.sourceUri) ?: return track
        if (art == track.artworkUrl) return track
        tracks.setCover(track.id, art, t.album)
        return track.copy(artworkUrl = art, album = track.album ?: t.album)
    }

    private suspend fun notify(job: ImportJob, done: Int, waiting: Int, t: ImportJobTrack) {
        val now = System.currentTimeMillis()
        if (now - lastNotified < 1000 && done < job.total) return
        lastNotified = now
        val more = if (waiting > 1) " · ${waiting - 1} more waiting" else ""
        runCatching { setForeground(notification("${job.name}$more", "${t.artist} - ${t.title}", done, job.total)) }
    }

    private fun notification(title: String, text: String?, done: Int, total: Int): ForegroundInfo {
        val openImports = PendingIntent.getActivity(
            applicationContext, 1,
            Intent(applicationContext, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_IMPORTS, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val recentMissing = synchronized(missing) { missing.takeLast(6).reversed() }
        val n = NotificationCompat.Builder(applicationContext, StashApp.CHANNEL_IMPORTS)
            .setContentIntent(openImports)
            .apply {
                // pull the notification down to see what couldn't be found so far
                if (recentMissing.isNotEmpty()) setStyle(
                    NotificationCompat.InboxStyle()
                        .setBigContentTitle("Couldn't find ${missing.size} so far")
                        .also { style -> recentMissing.forEach { style.addLine(it) } }
                        .setSummaryText(text ?: "$done of $total")
                )
            }
            .setSmallIcon(R.drawable.ic_stat_stash)
            .setContentTitle("Moving playlists: $title")
            .setContentText(
                (text ?: "$done of $total") + if (missing.isNotEmpty()) " · ${missing.size} not found" else ""
            )
            .setSubText(if (total > 0) "$done / $total" else null)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(NOTIFICATION_ID, n)
    }

    companion object {
        private const val PARALLEL = 4
        private const val NOTIFICATION_ID = 0x4100
        private const val RETRY_MISSES_AFTER = 3L * 24 * 60 * 60 * 1000
        /** 1.10.0: playlist name as context (soundtracks under the game's name). bump when matching gets better again */
        const val MATCHER_CHANGED_AT = 1_792_972_800_000L
        const val MATCHER_VERSION = 6
    }
}
