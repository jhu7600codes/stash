package app.stash.providers

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * youtube audio through community youtube frontends (invidious / piped). their servers fetch
 * the audio from youtube and pass it on, so youtube never sees this phone's connection: no
 * vpn bot checks, no regional slowdown. the instance list comes from a community-kept json
 * (updated on launch, or by hand in settings); instances that work are remembered and tried first.
 */
@Singleton
class Frontends @Inject constructor(
    @ApplicationContext private val context: Context,
    http: OkHttpClient,
) {
    data class Instance(val kind: String, val api: String) {
        val host get() = api.substringAfter("://").substringBefore('/')
    }

    data class ListInfo(val instances: Int, val updatedAt: Long, val error: String? = null)

    // instances are often slow home servers: don't let one hang a song
    private val http = http.newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    // yt2009 redirects only once its copy exists
    private val slowHttp = http.newBuilder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
    private val file get() = File(context.filesDir, "frontends.json")
    private val prefs get() = context.getSharedPreferences("frontends", Context.MODE_PRIVATE)

    /** last time each instance worked (ms) / failed in a row */
    private val lastOk = ConcurrentHashMap<String, Long>()
    private val fails = ConcurrentHashMap<String, Int>()

    private val _info = MutableStateFlow(ListInfo(0, 0))
    val info: StateFlow<ListInfo> = _info

    @Volatile private var instances: List<Instance> = emptyList()

    private fun load(): List<Instance> {
        if (instances.isNotEmpty()) return instances
        val text = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()
            ?: runCatching { context.assets.open("frontends.json").bufferedReader().use { it.readText() } }.getOrNull()
            ?: return emptyList()
        instances = parse(text)
        _info.value = ListInfo(instances.size, prefs.getLong("updated", 0))
        return instances
    }

    /** downloads the community list again (on launch when it's older than [maxAgeMs], or by hand) */
    suspend fun update(force: Boolean = false, maxAgeMs: Long = 6 * 60 * 60 * 1000L): ListInfo {
        load()
        val updated = prefs.getLong("updated", 0)
        if (!force && System.currentTimeMillis() - updated < maxAgeMs) return _info.value
        val url = prefs.getString("list_url", null) ?: LIST_URL
        return runCatching {
            val text = httpIo { http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body!!.string() else error("HTTP ${r.code}") } }
            val parsed = parse(text)
            check(parsed.isNotEmpty()) { "the list was empty" }
            file.writeText(text)
            instances = parsed
            prefs.edit().putLong("updated", System.currentTimeMillis()).apply()
            ListInfo(parsed.size, System.currentTimeMillis()).also { _info.value = it }
        }.getOrElse { e ->
            Log.w(TAG, "frontend list update failed", e)
            _info.value.copy(error = e.message).also { _info.value = it }
        }
    }

    /**
     * a playable audio link for [videoId] from whichever instance answers first with one that
     * plays: invidious / piped in waves, and yt2009 alongside (slower: it serves its own cached mp4s)
     */
    suspend fun audioStream(videoId: String): StreamSource? =
        race(listOf({ invidiousOrPiped(videoId) }, { yt2009(videoId) }))

    private suspend fun invidiousOrPiped(videoId: String): StreamSource? {
        // known-good first, then never tried, then the ones that keep failing
        val ordered = load().filter { it.kind != "yt2009" }.sortedWith(
            compareByDescending<Instance> { lastOk[it.api] ?: 0L }.thenBy { fails[it.api] ?: 0 }
        )
        if (ordered.isEmpty()) return null
        // a few at a time, so a dead instance never costs more than one wave
        for (wave in ordered.chunked(WAVE)) {
            val found = race(wave.map { inst -> suspend { runCatching { fromInstance(inst, videoId) }.getOrNull().also { record(inst, it != null) } } }, WAVE_MS)
            if (found != null) return found
        }
        return null
    }

    private fun record(inst: Instance, ok: Boolean) {
        if (ok) { lastOk[inst.api] = System.currentTimeMillis(); fails.remove(inst.api) }
        else fails.merge(inst.api, 1, Int::plus)
    }

    private suspend fun fromInstance(inst: Instance, videoId: String): StreamSource? = httpIo {
        val url = when (inst.kind) {
            // local=true: the instance proxies the audio itself
            "invidious" -> {
                val o = get("${inst.api}/api/v1/videos/$videoId?local=true&fields=adaptiveFormats") ?: return@httpIo null
                val audio = o["adaptiveFormats"].asObjectList().filter { it.str("type").orEmpty().startsWith("audio/") }
                val best = audio.firstOrNull { it.str("itag") == "251" } ?: audio.firstOrNull { it.str("itag") == "140" }
                    ?: audio.maxByOrNull { it.str("bitrate")?.toLongOrNull() ?: 0 } ?: return@httpIo null
                best.str("url")?.let { if (it.startsWith("http")) it else inst.api.trimEnd('/') + it }
            }
            "piped" -> {
                val o = get("${inst.api}/streams/$videoId") ?: return@httpIo null
                o["audioStreams"].asObjectList()
                    .sortedWith(compareByDescending<JsonObject> { it.str("codec")?.contains("opus") == true }.thenByDescending { it.num("bitrate") ?: 0.0 })
                    .firstOrNull()?.str("url")
            }
            else -> null
        } ?: return@httpIo null
        if (StreamCheck.plays(http, url, UA)) StreamSource(url, mapOf("User-Agent" to UA), via = "${inst.kind.replaceFirstChar { it.uppercase() }} (${inst.host})") else null
    }

    /**
     * yt2009 serves youtube videos as its own mp4 copies: /get_video?video_id=ID/mp4 redirects
     * to /assets/ID.mp4 once it has one. a song it doesn't have yet takes it minutes to build,
     * so this gives up after [YT2009_MS]; asking still makes it start, so next time it's quick.
     */
    private suspend fun yt2009(videoId: String): StreamSource? {
        val servers = load().filter { it.kind == "yt2009" }
        if (servers.isEmpty()) return null
        return race(
            servers.map { inst ->
                suspend {
                    runCatching {
                        httpIo {
                            val req = Request.Builder().url("${inst.api}/get_video?video_id=$videoId/mp4")
                                .header("User-Agent", UA).header("Range", "bytes=0-4095").build()
                            slowHttp.newCall(req).execute().use { r ->
                                val type = r.header("Content-Type").orEmpty()
                                if ((r.code == 206 || r.code == 200) && type.startsWith("video/mp4"))
                                    StreamSource(r.request.url.toString(), mapOf("User-Agent" to UA), via = "yt2009 (${inst.host})")
                                else null
                            }
                        }
                    }.getOrNull().also { record(inst, it != null) }
                }
            },
            YT2009_MS,
        )
    }

    private fun get(url: String): JsonObject? =
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string()?.let { lenientJson.parseToJsonElement(it).jsonObject } else null
        }

    companion object {
        private const val TAG = "Frontends"
        const val LIST_URL = "http://144.31.189.129/notPipe.json"
        private const val WAVE = 4
        private const val WAVE_MS = 9_000L
        private const val YT2009_MS = 25_000L
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        /** {"invidious": [url…], "piped": ["api,proxy"…], "yt2009": […], …}: the kinds that give audio */
        fun parse(text: String): List<Instance> {
            val root = runCatching { lenientJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
            fun urls(key: String) = (root[key] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
            return urls("invidious").map { Instance("invidious", it.trimEnd('/')) } +
                urls("piped").map { Instance("piped", it.substringBefore(',').trim().trimEnd('/')) } +
                urls("yt2009").map { Instance("yt2009", it.trimEnd('/')) }
        }
    }
}
