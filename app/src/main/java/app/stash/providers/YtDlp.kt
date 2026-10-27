package app.stash.providers

import android.content.Context
import android.util.Log
import app.stash.data.prefs.Settings
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * Wraps youtubedl-android. Python + yt-dlp get unpacked on first start, then the bundled yt-dlp
 * is updated every app start because youtube breaks old versions all the time.
 */
@Singleton
class YtDlp @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: Settings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ready = CompletableDeferred<Unit>()

    /** held while yt-dlp is replacing itself so nothing runs a half-written binary */
    private val updateLock = Mutex()

    private val _status = MutableStateFlow("Starting")
    val status: StateFlow<String> = _status

    fun start() {
        scope.launch {
            try {
                YoutubeDL.getInstance().init(context)
                ready.complete(Unit)
                _status.value = "Ready"
            } catch (e: Throwable) {
                // Throwable, not Exception: a python that can't load on this cpu (32-bit tvs) throws an
                // Error, and that used to take the whole app down at launch. stash plays fine without yt-dlp
                Log.e(TAG, "yt-dlp init failed", e)
                ready.completeExceptionally(e)
                _status.value = "Failed to start: ${e.message}"
                return@launch
            }
            // songs that need yt-dlp wait while it updates, so not on every start: once a day
            val last = settings.current().lastYtDlpUpdate
            if (System.currentTimeMillis() - last > 24L * 60 * 60 * 1000) update()
        }
    }

    /** returns a human readable result for the settings screen */
    suspend fun update(): String = withContext(Dispatchers.IO) {
        ready.await()
        updateLock.withLock {
            _status.value = "Updating"
            val msg = try {
                val result = YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
                val version = YoutubeDL.getInstance().version(context) ?: "?"
                settings.setYtDlpUpdated(version)
                if (result == YoutubeDL.UpdateStatus.DONE) "Updated to $version" else "Already on $version"
            } catch (e: Exception) {
                Log.w(TAG, "yt-dlp update failed", e)
                "Update failed: ${e.message}"
            }
            _status.value = "Ready"
            msg
        }
    }

    suspend fun version(): String? = withContext(Dispatchers.IO) {
        runCatching { ready.await(); YoutubeDL.getInstance().version(context) }.getOrNull()
    }

    /**
     * Runs yt-dlp. Cancelling the coroutine kills the process. [onProgress] gets 0..100.
     */
    suspend fun run(
        target: String,
        options: List<Pair<String, String?>>,
        /** downloads: their own lane, so a playlist download never makes a song you tapped wait */
        background: Boolean = false,
        onProgress: ((Float) -> Unit)? = null,
    ): YoutubeDLResponse {
        ready.await()
        updateLock.withLock { } // wait out an update in progress
        // each run is a whole python process (~100MB); never more than a few at once
        return (if (background) backgroundProcesses else processes).withPermit { start(target, options, onProgress) }
    }

    private val processes = kotlinx.coroutines.sync.Semaphore(2)
    private val backgroundProcesses = kotlinx.coroutines.sync.Semaphore(2)

    private suspend fun start(
        target: String,
        options: List<Pair<String, String?>>,
        onProgress: ((Float) -> Unit)?,
    ): YoutubeDLResponse {
        val processId = UUID.randomUUID().toString()
        val request = YoutubeDLRequest(target).apply {
            addOption("--no-warnings")
            addOption("--no-check-certificates")
            // keeps youtube's solved signature code between runs; without it every song re-solves it
            addOption("--cache-dir", java.io.File(context.cacheDir, "yt-dlp").absolutePath)
            if ("youtube.com" in target || "youtu.be" in target || target.startsWith("ytsearch")) {
                val cookies = YouTubeLogin.cookieFile(context)
                if (cookies.exists()) {
                    // logged in: yt-dlp acts as your browser session, past the bot checks
                    addOption("--cookies", cookies.absolutePath)
                } else {
                    // anonymous: skip loading the watch page and client configs, which is most of
                    // the requests yt-dlp makes before it even asks for the audio
                    addOption("--extractor-args", "youtube:player_skip=webpage,configs")
                }
            }
            options.forEach { (k, v) -> if (v == null) addOption(k) else addOption(k, v) }
        }
        return suspendCancellableCoroutine { cont ->
            val worker = thread(name = "yt-dlp") {
                try {
                    val response = YoutubeDL.getInstance().execute(request, processId) { progress, _, _ ->
                        onProgress?.invoke(progress)
                    }
                    if (cont.isActive) cont.resumeWith(Result.success(response))
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resumeWith(Result.failure(ProviderException(cleanError(e), e)))
                }
            }
            cont.invokeOnCancellation {
                YoutubeDL.getInstance().destroyProcessById(processId)
                worker.interrupt()
            }
        }
    }

    private fun cleanError(e: Throwable): String {
        val msg = e.message ?: e.javaClass.simpleName
        // yt-dlp dumps a whole stderr; the useful line starts with ERROR:
        return msg.lines().lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim()
            ?: msg.lines().firstOrNull { it.isNotBlank() }?.take(200)
            ?: msg
    }

    companion object {
        private const val TAG = "YtDlp"
    }
}
