package app.stash.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.stash.R
import app.stash.StashApp
import app.stash.data.db.TrackDao
import app.stash.data.prefs.Settings
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

/** "shrink my downloads": re-encodes songs already on the phone down to the chosen quality */
@HiltWorker
class ShrinkWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val tracks: TrackDao,
    private val compressor: Compressor,
    private val settings: Settings,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val quality = settings.current().downloadQuality
        val songs = tracks.downloaded()
        var saved = 0L
        songs.forEachIndexed { i, t ->
            if (isStopped) return Result.success()
            runCatching { setForeground(info("Shrinking downloads", "${i + 1} of ${songs.size} · ${t.title}", i, songs.size)) }
            val file = t.localPath?.let(::File)?.takeIf { it.exists() } ?: return@forEachIndexed
            val before = file.length()
            val kept = runCatching { compressor.shrinkIfNeeded(file, quality) }.getOrNull() ?: return@forEachIndexed
            if (kept != file) {
                saved += before - kept.length()
                tracks.markDownloaded(t.id, kept.absolutePath)
            }
        }
        val freed = Formatter.formatShortFileSize(applicationContext, saved)
        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(
                DONE_ID,
                NotificationCompat.Builder(applicationContext, StashApp.CHANNEL_DOWNLOADS)
                    .setSmallIcon(R.drawable.ic_stat_stash)
                    .setContentTitle("Downloads shrunk")
                    .setContentText(if (saved > 0) "Freed up $freed" else "Everything was already small enough")
                    .setAutoCancel(true)
                    .build(),
            )
        }
        return Result.success()
    }

    private fun info(title: String, text: String, done: Int, total: Int): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, StashApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_stash)
            .setContentTitle(title)
            .setContentText(text)
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
        private const val PROGRESS_ID = 0x5200
        private const val DONE_ID = 0x5201
    }
}
