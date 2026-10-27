package app.stash.importer

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.stash.R
import app.stash.StashApp
import app.stash.data.db.ImportDao
import app.stash.data.db.LIKED_PLAYLIST_ID
import app.stash.data.db.PlaylistDao
import app.stash.data.db.PlaylistTrack
import app.stash.data.db.StashDatabase
import app.stash.data.db.Track
import app.stash.data.db.TrackDao
import app.stash.data.db.UnmatchedTrack
import app.stash.providers.Resolver
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * "rescan failed songs": tries every song that couldn't be found again, against all current
 * sources (new ones like audius included). found songs go back into their exact spot.
 */
@HiltWorker
class RescanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val db: StashDatabase,
    private val playlists: PlaylistDao,
    private val tracks: TrackDao,
    private val imports: ImportDao,
    private val resolver: Resolver,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        imports.forgetMisses()
        val missing = playlists.allUnmatched()
        // the playlist each song is in, which helps find soundtracks (see Resolver.match)
        val names = playlists.all().associate { it.id to it.name }
        if (missing.isEmpty()) return Result.success(workDataOf(KEY_TOTAL to 0, KEY_FOUND to 0))
        val done = AtomicInteger()
        val found = AtomicInteger()
        // newest first, shown on the rescan screen
        val foundNames = java.util.Collections.synchronizedList(mutableListOf<String>())
        val gate = Semaphore(4)
        runCatching { setForeground(info(0, missing.size, 0)) }
        coroutineScope {
            missing.map { u ->
                async {
                    gate.withPermit {
                        if (isStopped) return@withPermit
                        if (due()) setProgress(progress(done.get(), missing.size, found.get(), "${u.artist} - ${u.title}", foundNames))
                        val hit = runCatching { resolver.match(u.title, u.artist, u.durationMs, u.album, names[u.playlistId], loose = true) }.getOrNull()
                        if (hit != null) {
                            place(u, hit)
                            found.incrementAndGet()
                            foundNames.add(0, "${u.artist} - ${u.title}")
                        }
                        val n = done.incrementAndGet()
                        // at most about once a second: posting a notification per song (4 at a time)
                        // floods android's notification service until the whole system stops responding
                        if (due() || n == missing.size) {
                            runCatching { setForeground(info(n, missing.size, found.get())) }
                            setProgress(progress(n, missing.size, found.get(), null, foundNames))
                        }
                    }
                }
            }.awaitAll()
        }
        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(
                DONE_ID,
                NotificationCompat.Builder(applicationContext, StashApp.CHANNEL_IMPORTS)
                    .setSmallIcon(R.drawable.ic_stat_stash)
                    .setContentIntent(openRescan())
                    .setContentTitle("Rescan done")
                    .setContentText("Found ${found.get()} of ${missing.size} missing songs")
                    .setAutoCancel(true)
                    .build(),
            )
        }
        return Result.success(progress(missing.size, missing.size, found.get(), null, foundNames))
    }

    private val lastUpdate = java.util.concurrent.atomic.AtomicLong(0)

    /** true at most once per [UPDATE_MS], for whichever coroutine asks first */
    private fun due(): Boolean {
        val now = System.currentTimeMillis()
        val last = lastUpdate.get()
        return now - last >= UPDATE_MS && lastUpdate.compareAndSet(last, now)
    }

    /** work data is capped at 10KB, so only the latest found songs ride along */
    private fun progress(done: Int, total: Int, found: Int, current: String?, names: List<String>) = workDataOf(
        KEY_DONE to done, KEY_TOTAL to total, KEY_FOUND to found, KEY_CURRENT to current,
        KEY_FOUND_LIST to synchronized(names) { names.take(40).map { it.take(120) }.toTypedArray() },
    )

    private suspend fun place(u: UnmatchedTrack, hit: Track) = db.withTransaction {
        // keep the name/artist the song had on spotify, like the importer does
        val saved = tracks.ensure(hit.copy(title = u.title, artist = u.artist, album = u.album ?: hit.album))
        tracks.addToLibrary(saved.id)
        if (u.playlistId == LIKED_PLAYLIST_ID) {
            if (u.likedAt > 0) tracks.importLike(saved.id, u.likedAt) else tracks.setLiked(saved.id, true)
        } else {
            // its old spot can only be taken if something was added by hand there; then it goes last
            if (playlists.insertTrack(PlaylistTrack(u.playlistId, saved.id, u.position)) == -1L) {
                playlists.insertTrack(PlaylistTrack(u.playlistId, saved.id, playlists.nextPosition(u.playlistId)))
            }
            playlists.touch(u.playlistId)
        }
        playlists.deleteUnmatched(u.id)
    }

    private fun openRescan() = android.app.PendingIntent.getActivity(
        applicationContext, 2,
        android.content.Intent(applicationContext, app.stash.MainActivity::class.java).putExtra(app.stash.MainActivity.EXTRA_OPEN_RESCAN, true),
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun info(done: Int, total: Int, found: Int): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, StashApp.CHANNEL_IMPORTS)
            .setSmallIcon(R.drawable.ic_stat_stash)
            .setContentIntent(openRescan())
            .setContentTitle("Rescanning missing songs")
            .setContentText("$done of $total checked · $found found")
            .setProgress(total, done, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(PROGRESS_ID, n)
    }

    companion object {
        private const val UPDATE_MS = 1_000L
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FOUND = "found"
        const val KEY_CURRENT = "current"
        const val KEY_FOUND_LIST = "found_list"
        private const val PROGRESS_ID = 0x4200
        private const val DONE_ID = 0x4201
    }
}
