package app.stash.download

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import app.stash.data.prefs.DownloadQuality
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * makes downloads smaller by re-encoding them to aac with media3's transformer (the phone's own
 * encoder, no ffmpeg). only touches files that are clearly bigger than the chosen quality, and
 * keeps the original if the result isn't actually smaller.
 */
@Singleton
@OptIn(UnstableApi::class)
class Compressor @Inject constructor(@ApplicationContext private val context: Context) {

    /** returns the file to keep: [file] itself, or the smaller re-encoded one */
    suspend fun shrinkIfNeeded(file: File, quality: DownloadQuality): File {
        val target = quality.aacBitrate ?: return file
        val kbps = bitrateKbps(file) ?: return file
        // a little headroom: re-encoding a 100 kbps file to 96 isn't worth the quality hit
        if (kbps * 1000 <= target * 1.25) return file

        val base = file.name.substringBefore('.')
        val tmp = File(file.parentFile, "$base.shrinking.m4a")
        tmp.delete()
        try {
            toAac(file, tmp, target)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        if (!tmp.exists() || tmp.length() == 0L || tmp.length() >= file.length()) {
            tmp.delete()
            return file
        }
        val out = File(file.parentFile, "$base.m4a")
        file.delete()
        if (!tmp.renameTo(out)) throw IllegalStateException("Couldn't save the smaller file")
        return out
    }

    fun bitrateKbps(file: File): Int? {
        val durationMs = runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(file.absolutePath)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } finally {
                    release()
                }
            }
        }.getOrNull() ?: return null
        if (durationMs <= 0) return null
        return (file.length() * 8 / durationMs).toInt() // bits per ms = kbps
    }

    /** transformer needs a looper, so it runs on the main thread; the encoding itself is off-thread */
    private suspend fun toAac(input: File, output: File, bitrate: Int) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(bitrate).build())
                        .build()
                )
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input))).setRemoveVideo(true).build()
            transformer.start(item, output.absolutePath)
            cont.invokeOnCancellation { transformer.cancel() }
        }
    }
}
