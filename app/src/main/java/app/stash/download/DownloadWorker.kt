package app.stash.download

import kotlinx.coroutines.sync.withPermit
import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.stash.R
import app.stash.StashApp
import app.stash.data.db.DownloadState
import app.stash.data.db.Track
import app.stash.data.db.TrackDao
import app.stash.data.prefs.DownloadQuality
import app.stash.data.prefs.Settings
import app.stash.providers.DownloadSource
import app.stash.providers.ProviderException
import app.stash.providers.Resolver
import app.stash.providers.YtDlp
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dao: TrackDao,
    private val resolver: Resolver,
    private val downloads: Downloads,
    private val ytDlp: YtDlp,
    private val http: OkHttpClient,
    private val compressor: Compressor,
    private val settings: Settings,
) : CoroutineWorker(context, params) {

    private val trackId get() = inputData.getString(KEY_TRACK)!!
    private var lastReported = -1f
    private var lastReportedAt = 0L

    override suspend fun doWork(): Result = downloads.slots.withPermit { download() }

    private suspend fun download(): Result {
        val track = dao.get(trackId) ?: return Result.failure()
        if (track.isDownloaded && track.localPath?.let { File(it).exists() } == true) return Result.success()

        dao.setDownloadState(track.id, DownloadState.DOWNLOADING, track.downloadProgress)
        runCatching { setForeground(foregroundInfo(track, 0f)) }

        return try {
            val source = resolver.downloadSourceFor(track)
            val base = downloads.baseName(track.id)
            val quality = settings.current().downloadQuality
            val raw = when (source) {
                is DownloadSource.YtDlp -> viaYtDlp(track, source, base, quality)
                is DownloadSource.Http -> viaHttp(track, source, base)
            }
            // a big mp3 from an open source (or a youtube stream that had no small version) gets shrunk
            val file = runCatching { compressor.shrinkIfNeeded(raw, quality) }.getOrDefault(raw)
            dao.markDownloaded(track.id, file.absolutePath)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download failed for ${track.title}", e)
            if (runAttemptCount < MAX_ATTEMPTS) {
                dao.setDownloadState(track.id, DownloadState.QUEUED, track.downloadProgress, e.message)
                Result.retry()
            } else {
                dao.setDownloadState(track.id, DownloadState.FAILED, 0f, e.message ?: "Download failed")
                Result.failure()
            }
        }
    }

    private suspend fun viaYtDlp(track: Track, source: DownloadSource.YtDlp, base: String, quality: DownloadQuality): File {
        val dir = downloads.dir
        // yt-dlp reports progress on its own thread; hop it over to a coroutine for the db writes
        val progress = MutableStateFlow(0f)
        val reporter = CoroutineScope(currentCoroutineContext()).launch { progress.collect { report(track, it) } }
        try {
            downloadWithYtDlp(source, dir, base, quality) { progress.value = it }
        } finally {
            reporter.cancel()
        }
        return dir.listFiles { f -> f.name.startsWith("$base.") && !f.name.endsWith(".part") && !f.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.lastModified() }
            ?: throw ProviderException("yt-dlp finished but no file was written")
    }

    private suspend fun downloadWithYtDlp(source: DownloadSource.YtDlp, dir: File, base: String, quality: DownloadQuality, onProgress: (Float) -> Unit) {
        ytDlp.run(
            source.pageUrl,
            listOf(
                // soundcloud's hls comes as aac (needs ffmpeg to stitch) or mp3 (doesn't): take mp3
                "-f" to if ("soundcloud.com" in source.pageUrl) "bestaudio[ext=mp3]/bestaudio" else quality.ytFormat,
                "-o" to "${dir.absolutePath}/$base.%(ext)s",
                "-c" to null,
                "--no-playlist" to null,
                "--no-mtime" to null,
                "--newline" to null,
            ),
            background = true,
        ) { pct -> onProgress(pct / 100f) }
    }

    /** plain http with range-based resume from a .part file */
    private suspend fun viaHttp(track: Track, source: DownloadSource.Http, base: String): File = withContext(Dispatchers.IO) {
        val dir = downloads.dir
        val part = File(dir, "$base.${source.extension}.part")
        val done = File(dir, "$base.${source.extension}")
        val have = if (part.exists()) part.length() else 0L

        val req = Request.Builder().url(source.url).apply {
            source.headers.forEach { (k, v) -> header(k, v) }
            if (have > 0) header("Range", "bytes=$have-")
        }.build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ProviderException("Server said ${resp.code}")
            val append = resp.code == 206
            val body = resp.body ?: throw ProviderException("Empty response")
            val total = body.contentLength().let { if (it > 0) it + (if (append) have else 0) else -1 }
            var written = if (append) have else 0L
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (total > 0) report(track, written.toFloat() / total)
                    }
                }
            }
        }
        if (!part.renameTo(done)) throw ProviderException("Couldn't finish the file")
        done
    }

    private suspend fun report(track: Track, fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        // every 5% and at most twice a second: several downloads at once each posting a
        // notification and a database write per percent hammered the system and every list
        val now = System.currentTimeMillis()
        if (f < 1f && (f - lastReported < 0.05f || now - lastReportedAt < 500)) return
        lastReported = f
        lastReportedAt = now
        dao.setDownloadProgress(track.id, f)
        runCatching { setForeground(foregroundInfo(track, f)) }
    }

    private fun foregroundInfo(track: Track, progress: Float): ForegroundInfo {
        val n: Notification = NotificationCompat.Builder(applicationContext, StashApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_stash)
            .setContentTitle(track.title)
            .setContentText("Downloading · ${track.artist}")
            .setProgress(100, (progress * 100).toInt(), progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        val id = NOTIFICATION_BASE + (track.id.hashCode() and 0xffff)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, n)
        }
    }

    companion object {
        const val KEY_TRACK = "track"
        private const val TAG = "DownloadWorker"
        private const val MAX_ATTEMPTS = 3
        private const val NOTIFICATION_BASE = 0x5000
    }
}
