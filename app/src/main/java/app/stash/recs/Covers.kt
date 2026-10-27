package app.stash.recs

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.stash.data.db.CoverCheck
import app.stash.data.db.ImportDao
import app.stash.data.db.Track
import app.stash.data.db.TrackDao
import app.stash.providers.Matcher
import app.stash.providers.asObjectList
import app.stash.providers.getText
import app.stash.providers.httpIo
import app.stash.providers.lenientJson
import app.stash.providers.num
import app.stash.providers.str
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** real album covers: spotify's when the song came from spotify, apple music's otherwise */
@Singleton
class Covers @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
) {
    data class Cover(val url: String, val album: String?)

    /** spotify:track:… -> its album cover, through spotify's public oembed (no key) */
    suspend fun spotify(uri: String?): String? {
        if (uri == null || !uri.startsWith("spotify:track:")) return null
        val url = "https://open.spotify.com/oembed".toHttpUrl().newBuilder().addQueryParameter("url", uri).build()
        return runCatching {
            lenientJson.parseToJsonElement(httpIo { http.getText(url.toString()) }).jsonObject.str("thumbnail_url")
        }.getOrNull()
    }

    /** a spotify playlist / album link -> its cover (custom playlist covers included), via oembed */
    suspend fun spotifyLink(link: String): String? {
        val url = "https://open.spotify.com/oembed".toHttpUrl().newBuilder().addQueryParameter("url", link.trim()).build()
        return runCatching {
            lenientJson.parseToJsonElement(httpIo { http.getText(url.toString()) }).jsonObject.str("thumbnail_url")
        }.getOrNull()
    }

    /** the apple music catalog's cover for a song, through the itunes search api (no key) */
    suspend fun apple(title: String, artist: String, durationMs: Long): Cover? {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", "$artist $title")
            .addQueryParameter("entity", "song")
            .addQueryParameter("media", "music")
            .addQueryParameter("limit", "10")
            .build()
        val results = lenientJson.parseToJsonElement(httpIo { http.getText(url.toString()) }).jsonObject["results"].asObjectList()
        val best = results.map { r ->
            val asTrack = Track(
                id = "", title = r.str("trackName") ?: "", artist = r.str("artistName") ?: "",
                durationMs = (r.num("trackTimeMillis") ?: 0.0).toLong(), provider = "", sourceId = "",
            )
            r to Matcher.score(asTrack, title, artist, durationMs)
        }.filter { it.second >= Matcher.ACCEPT }.maxByOrNull { it.second }?.first ?: return null
        val art = best.str("artworkUrl100") ?: return null
        return Cover(art.replace("100x100bb", "600x600bb"), best.str("collectionName"))
    }

    /** runs [CoverWorker]; it keeps going until every saved song has been tried once */
    fun fixInBackground() {
        val request = OneTimeWorkRequestBuilder<CoverWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("stash_covers", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

/** swaps youtube video thumbnails on saved songs for the real album cover, slowly (apple rate-limits) */
@HiltWorker
class CoverWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val covers: Covers,
    private val tracks: TrackDao,
    private val checks: ImportDao,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        repairImports()
        repairLiked()
        while (!isStopped) {
            val batch = checks.needingCovers()
            if (batch.isEmpty()) break
            for (t in batch) {
                val cover = runCatching { covers.apple(t.title, t.artist, t.durationMs) }.getOrNull()
                if (cover != null) tracks.setCover(t.id, cover.url, cover.album)
                checks.coverChecked(CoverCheck(t.id))
                delay(APPLE_DELAY_MS)
            }
        }
        return Result.success()
    }

    /** once: give songs imported by older versions the spotify cover they should have had */
    private suspend fun repairImports() {
        val prefs = applicationContext.getSharedPreferences("stash_covers", Context.MODE_PRIVATE)
        if (prefs.getInt("import_repair", 0) >= 1) return
        for (r in runCatching { checks.coverRepairs() }.getOrDefault(emptyList())) {
            if (isStopped) return
            val art = r.artworkUrl ?: covers.spotify(r.sourceUri)?.also { delay(200) } ?: continue
            tracks.setCover(r.trackId, art, r.album)
        }
        prefs.edit().putInt("import_repair", 1).apply()
    }

    /** the same for imported liked songs, found through the importer's match cache */
    private suspend fun repairLiked() {
        val prefs = applicationContext.getSharedPreferences("stash_covers", Context.MODE_PRIVATE)
        if (prefs.getInt("liked_repair", 0) >= 1) return
        for (t in runCatching { checks.likedImports() }.getOrDefault(emptyList())) {
            if (isStopped) return
            val key = Matcher.normalize(t.artist.split(',').first()) + "|" + Matcher.normalize(t.title)
            val trackId = checks.cached(key)?.trackId ?: continue
            val track = tracks.get(trackId) ?: continue
            val art = t.artworkUrl ?: covers.spotify(t.sourceUri)?.also { delay(200) } ?: continue
            if (art != track.artworkUrl) tracks.setCover(trackId, art, t.album)
        }
        prefs.edit().putInt("liked_repair", 1).apply()
    }

    companion object {
        // the itunes api allows about 20 requests a minute
        private const val APPLE_DELAY_MS = 3_000L
    }
}
