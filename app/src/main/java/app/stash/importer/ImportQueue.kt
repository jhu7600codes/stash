package app.stash.importer

import android.content.Context
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.stash.data.db.ImportDao
import app.stash.data.db.ImportJob
import app.stash.data.db.ImportJobTrack
import app.stash.data.db.StashDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * playlists waiting to be moved into stash. they're saved in the db and worked through by
 * [ImportWorker] in the background, so 25 playlists keep going with the app closed and
 * pick up where they stopped if the phone kills it.
 */
data class RescanProgress(
    val running: Boolean,
    val done: Int,
    val total: Int,
    val found: Int,
    /** "artist - title" being searched right now */
    val current: String? = null,
    /** latest found songs, newest first */
    val foundNames: List<String> = emptyList(),
)

@Singleton
class ImportQueue @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: StashDatabase,
    private val dao: ImportDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val jobs = dao.observeJobs()
    val missing = dao.observeMissing()

    suspend fun enqueue(playlists: List<SourcePlaylist>) {
        db.withTransaction {
            for (p in playlists) {
                val jobId = dao.insertJob(
                    ImportJob(
                        name = p.name, origin = p.origin, originId = p.originId, coverUrl = p.coverUrl,
                        intoLiked = isLikedSongs(p), total = p.tracks.size,
                    )
                )
                dao.insertTracks(p.tracks.mapIndexed { i, t ->
                    ImportJobTrack(jobId, i, t.title, t.artist, t.album, t.durationMs, t.artworkUrl, t.sourceUri)
                })
            }
        }
        start()
    }

    /** chains a worker after any running one; it exits right away if there's nothing left */
    fun start() {
        val request = OneTimeWorkRequestBuilder<ImportWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** after a restart: if anything is still waiting, get the worker going again */
    fun resume() = scope.launch {
        if (dao.pendingCount() > 0) start()
        // the matcher got better: try everything that was missing once more, by itself
        val prefs = context.getSharedPreferences("stash_import", Context.MODE_PRIVATE)
        if (prefs.getInt("matcher", 1) < ImportWorker.MATCHER_VERSION) {
            prefs.edit().putInt("matcher", ImportWorker.MATCHER_VERSION).apply()
            if (db.playlists().allUnmatched().isNotEmpty()) rescanMissing()
        }
    }

    fun cancel(jobId: Long) = scope.launch { dao.cancel(jobId) }

    fun cancelAll() = scope.launch {
        dao.cancelAll()
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    fun clearFinished() = scope.launch { dao.clearFinished() }

    /** where the rescan is at: null when none has run (or it was a while ago) */
    val rescan: Flow<RescanProgress?> = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(RESCAN).map { infos ->
        val info = infos.lastOrNull() ?: return@map null
        val data = if (info.state.isFinished) info.outputData else info.progress
        RescanProgress(
            running = !info.state.isFinished,
            done = data.getInt(RescanWorker.KEY_DONE, 0),
            total = data.getInt(RescanWorker.KEY_TOTAL, 0),
            found = data.getInt(RescanWorker.KEY_FOUND, 0),
            current = data.getString(RescanWorker.KEY_CURRENT),
            foundNames = data.getStringArray(RescanWorker.KEY_FOUND_LIST)?.toList().orEmpty(),
        )
    }

    /** search every not-found song again (see [RescanWorker]); [restart] drops a running one first */
    fun rescanMissing(restart: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<RescanWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            RESCAN, if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request,
        )
    }

    fun stopRescan() {
        WorkManager.getInstance(context).cancelUniqueWork(RESCAN)
    }

    companion object {
        const val WORK = "stash_import"
        const val TAG = "stash_import"
        private const val RESCAN = "stash_rescan"

        fun isLikedSongs(p: SourcePlaylist) =
            p.originId == "liked" || p.name.trim().lowercase() in setOf("liked songs", "liked", "your liked songs")
    }
}
