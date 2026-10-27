package app.stash.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.stash.data.db.DownloadState
import app.stash.data.db.Track
import app.stash.data.db.TrackDao
import app.stash.data.prefs.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** the download queue. each track is one unique workmanager job, state lives on the track row */
@Singleton
class Downloads @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: TrackDao,
    private val settings: Settings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val work get() = WorkManager.getInstance(context)

    /**
     * downloads actually running at once. a whole playlist is queued as one worker per song and
     * workmanager would start them all together, each with its own python (yt-dlp) process and
     * notification, which is enough to run a phone out of memory. the rest wait here, quietly.
     */
    val slots = kotlinx.coroutines.sync.Semaphore(2)

    val dir: File get() = File(context.filesDir, "music").apply { mkdirs() }

    fun enqueue(tracks: List<Track>) = scope.launch {
        val wifiOnly = settings.current().wifiOnlyDownloads
        for (t in tracks) {
            val saved = dao.ensure(t)
            if (saved.isDownloaded && saved.localPath?.let { File(it).exists() } == true) continue
            dao.addToLibrary(saved.id)
            dao.setDownloadState(saved.id, DownloadState.QUEUED)
            schedule(saved.id, wifiOnly, ExistingWorkPolicy.KEEP)
        }
    }

    fun enqueue(track: Track) = enqueue(listOf(track))

    private fun schedule(trackId: String, wifiOnly: Boolean, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_TRACK to trackId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        work.enqueueUniqueWork(workName(trackId), policy, request)
    }

    fun cancel(track: Track) = scope.launch {
        work.cancelUniqueWork(workName(track.id))
        partialFiles(track.id).forEach { it.delete() }
        dao.clearDownload(track.id)
    }

    fun delete(track: Track) = scope.launch {
        work.cancelUniqueWork(workName(track.id))
        track.localPath?.let { File(it).delete() }
        partialFiles(track.id).forEach { it.delete() }
        dao.clearDownload(track.id)
    }

    fun delete(tracks: List<Track>) = tracks.forEach { delete(it) }

    fun retry(track: Track) = scope.launch {
        dao.setDownloadState(track.id, DownloadState.QUEUED)
        schedule(track.id, settings.current().wifiOnlyDownloads, ExistingWorkPolicy.REPLACE)
    }

    fun retryAllFailed() = scope.launch {
        val wifiOnly = settings.current().wifiOnlyDownloads
        dao.failedDownloads().forEach {
            dao.setDownloadState(it.id, DownloadState.QUEUED)
            schedule(it.id, wifiOnly, ExistingWorkPolicy.REPLACE)
        }
    }

    fun cancelAll() = scope.launch {
        work.cancelAllWorkByTag(TAG)
        dao.pendingDownloads().forEach { dao.clearDownload(it.id) }
        dao.failedDownloads().forEach { dao.clearDownload(it.id) }
    }

    /** wifi-only changed: re-schedule everything still waiting with the new network constraint */
    fun setWifiOnly(on: Boolean) = scope.launch {
        settings.setWifiOnly(on)
        dao.pendingDownloads().filter { it.downloadState == DownloadState.QUEUED }.forEach {
            schedule(it.id, on, ExistingWorkPolicy.REPLACE)
        }
    }

    /** a track stuck on "downloading" after the app was killed goes back in the queue (KEEP = no dupes) */
    fun recoverAfterRestart() = scope.launch {
        val wifiOnly = settings.current().wifiOnlyDownloads
        dao.pendingDownloads().forEach { schedule(it.id, wifiOnly, ExistingWorkPolicy.KEEP) }
    }

    /** re-encode what's already downloaded to the current quality (see [ShrinkWorker]) */
    fun shrinkAll() {
        work.enqueueUniqueWork(
            "stash_shrink", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ShrinkWorker>().setConstraints(
                Constraints.Builder().setRequiresStorageNotLow(false).setRequiresBatteryNotLow(true).build()
            ).build(),
        )
    }

    fun usedBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0

    fun baseName(trackId: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(trackId.toByteArray())
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }

    fun partialFiles(trackId: String): List<File> {
        val base = baseName(trackId)
        return dir.listFiles { f -> f.name.startsWith("$base.") && (f.name.endsWith(".part") || f.name.endsWith(".ytdl") || f.name.contains(".part-")) }
            ?.toList().orEmpty()
    }

    companion object {
        const val TAG = "stash_download"
        fun workName(trackId: String) = "dl_$trackId"
    }
}
