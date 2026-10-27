package app.stash.recs

import android.content.Context
import app.stash.providers.Matcher
import app.stash.providers.httpIo
import app.stash.providers.lenientJson
import app.stash.providers.num
import app.stash.providers.obj
import app.stash.providers.str
import app.stash.providers.asObjectList
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * musixmatch through its iphone app's api, the way EeveeSpotify does it: synced lyrics, with
 * musixmatch's community translation in the same request. it needs a user token: your own
 * (settings → lyrics → musixmatch token: the musixmatch app's "copy debug info", or the token
 * itself) or, without one, an anonymous token the api hands out. lrclib is used whenever this
 * has nothing or refuses.
 */
@Singleton
class Musixmatch @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
) {
    data class Found(val lyrics: SongLyrics, val commontrackId: Long?)

    /** a token pasted in settings (yours, from the musixmatch app); null = anonymous */
    val userToken: String? get() = prefs.getString("user_token", null)

    /** takes the musixmatch app's debug info or a bare token; false if there's no token in it */
    fun setUserToken(raw: String?): Boolean {
        if (raw.isNullOrBlank()) { prefs.edit().remove("user_token").apply(); return true }
        val token = parseToken(raw) ?: return false
        prefs.edit().putString("user_token", token).putString("user_app", appIdFor(raw)).apply()
        return true
    }

    /** a token only works with the app it came from: the android app's or the iphone's */
    private val appId: String get() = if (userToken != null) prefs.getString("user_app", null) ?: IOS else IOS

    /** a refused personal token: try it as the other app's (a bare token doesn't say which) */
    private fun switchUserApp() {
        prefs.edit().putString("user_app", if (appId == ANDROID) IOS else ANDROID).apply()
    }

    private val prefs get() = context.getSharedPreferences("musixmatch", Context.MODE_PRIVATE)

    private fun url(method: String) = "$BASE$method".toHttpUrl().newBuilder()
        .addQueryParameter("app_id", appId)
        .addQueryParameter("format", "json")

    private suspend fun call(url: HttpUrl): JsonObject? = httpIo {
        http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            ?.let { lenientJson.parseToJsonElement(it).jsonObject.obj("message") }
    }

    /** your token if you gave one, else the anonymous one (kept, renewed when refused) */
    private suspend fun token(renew: Boolean = false): String? {
        userToken?.let { return it }
        if (!renew) prefs.getString("anon_token", null)?.let { return it }
        val body = call(url("token.get").setQueryParameter("app_id", IOS).build())?.obj("body") ?: return null
        val token = body.str("user_token")?.takeIf { it.isNotBlank() && !it.all { c -> c == '0' } && "Upgrade" !in it } ?: return null
        prefs.edit().putString("anon_token", token).apply()
        return token
    }

    /** [translateTo]: also musixmatch's translation into that language, line by line, when it has one */
    suspend fun lyrics(title: String, artist: String, durationMs: Long, translateTo: String? = null): Found? {
        for (attempt in 0..1) {
            val t = token(renew = attempt == 1) ?: return null
            val u = url("macro.subtitles.get")
                .addQueryParameter("subtitle_format", "mxm")
                // word-by-word timing too, when musixmatch has it (apple music style karaoke)
                .addQueryParameter("namespace", "lyrics_richsynched")
                .addQueryParameter("optional_calls", "track.richsync")
                .apply {
                    if (translateTo != null) {
                        addQueryParameter("selected_language", translateTo)
                        addQueryParameter("part", "subtitle_translated")
                    }
                }
                .addQueryParameter("q_artist", Matcher.primaryArtist(artist))
                .addQueryParameter("q_track", Matcher.stripForSearch(title))
                .apply {
                    if (durationMs > 0) {
                        addQueryParameter("q_duration", (durationMs / 1000).toString())
                        addQueryParameter("f_subtitle_length", (durationMs / 1000).toString())
                    }
                }
                .addQueryParameter("usertoken", t)
                .build()
            val msg = call(u) ?: return null
            // 401: the token was refused. anonymous: get a new one; yours: try the other app's id
            if (msg.obj("header")?.num("status_code")?.toInt() == 401) {
                if (userToken != null) switchUserApp()
                continue
            }
            val calls = msg.obj("body")?.obj("macro_calls") ?: return null
            val track = calls.obj("matcher.track.get")?.obj("message")?.obj("body")?.obj("track")
            val sub = calls.obj("track.subtitles.get")?.obj("message")?.obj("body")?.get("subtitle_list")
                .asObjectList().firstOrNull()?.obj("subtitle")
            val subtitle = sub?.str("subtitle_body")
            // the translation comes along, one entry per line with its original (skipped when the
            // song is already in that language)
            val translatedPairs = sub?.obj("subtitle_translated")?.str("subtitle_body")
                ?.takeIf { translateTo != null && sub.str("subtitle_language") != translateTo }
                ?.let { body ->
                    runCatching {
                        (lenientJson.parseToJsonElement(body) as JsonArray).map { el ->
                            val o = el as? JsonObject
                            o?.str("original")?.trim() to o?.str("text")?.takeIf { it.isNotBlank() }
                        }
                    }.getOrNull()
                }
            var translated = translatedPairs?.map { it.second }
            // richsync: every line with its words' start times
            val rich = calls.obj("track.richsync.get")?.obj("message")?.obj("body")?.obj("richsync")
                ?.takeIf { it.str("restricted") != "1" }?.str("richsync_body")
                ?.let { body -> runCatching { parseRichsync(body) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }
            if (rich != null && translatedPairs != null) {
                // richsync lines can differ from the subtitle's: match translations by the line's text
                val byOriginal = translatedPairs.mapNotNull { (o, t) -> if (o != null && t != null) o to t else null }.toMap()
                translated = rich.map { byOriginal[it.text.trim()] }
            }
            val lines = rich?.map { it.startMs to it.text } ?: subtitle?.let { body ->
                runCatching {
                    (lenientJson.parseToJsonElement(body) as JsonArray).mapNotNull { el ->
                        val o = el as? JsonObject ?: return@mapNotNull null
                        val ms = ((o.obj("time")?.num("total") ?: return@mapNotNull null) * 1000).toLong()
                        ms to (o.str("text") ?: "")
                    }
                }.getOrNull()
            }.orEmpty()
            val plain = calls.obj("track.lyrics.get")?.obj("message")?.obj("body")?.obj("lyrics")?.str("lyrics_body")
                ?.substringBefore("*******")?.trim()
            if (lines.isEmpty() && plain.isNullOrBlank()) return null
            val lyrics = SongLyrics(
                lines, plain, source = "Musixmatch",
                words = rich?.map { it.words },
                translated = translated?.takeIf { it.size == lines.size && it.any { t -> t != null } },
                translatedTo = translateTo,
            )
            return Found(lyrics, track?.num("commontrack_id")?.toLong())
        }
        return null
    }

    /** musixmatch's community translation into [lang], as original line -> translated line */
    suspend fun translation(commontrackId: Long, lang: String): Map<String, String>? {
        val t = token() ?: return null
        val u = url("crowd.track.translations.get")
            .addQueryParameter("commontrack_id", commontrackId.toString())
            .addQueryParameter("selected_language", lang)
            .addQueryParameter("comment_format", "text")
            .addQueryParameter("part", "user")
            .addQueryParameter("translation_fields_set", "minimal")
            .addQueryParameter("usertoken", t)
            .build()
        val list = call(u)?.obj("body")?.get("translations_list").asObjectList()
        val map = list.mapNotNull { it.obj("translation") }.mapNotNull { tr ->
            val from = tr.str("subtitle_matched_line") ?: tr.str("matched_line") ?: return@mapNotNull null
            val to = tr.str("description") ?: return@mapNotNull null
            from.trim() to to.trim()
        }.toMap()
        return map.ifEmpty { null }
    }

    companion object {
        /** one richsync line: [words] are (start ms, text) with spaces as their own pieces */
        data class RichLine(val startMs: Long, val text: String, val words: List<Pair<Long, String>>)

        /** [{"ts":1.38,"te":1.56,"l":[{"c":"word","o":0},{"c":" ","o":0.03}…],"x":"the line"}…] */
        fun parseRichsync(body: String): List<RichLine> =
            (lenientJson.parseToJsonElement(body) as JsonArray).mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val ts = o.num("ts") ?: return@mapNotNull null
                val words = o["l"].asObjectList().mapNotNull { w ->
                    // raw content: str() drops blank values, and the spaces between words are pieces too
                    val c = (w["c"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
                    ((ts + (w.num("o") ?: 0.0)) * 1000).toLong() to c
                }
                RichLine((ts * 1000).toLong(), o.str("x") ?: words.joinToString("") { it.second }, words)
            }

        private const val BASE = "https://apic.musixmatch.com/ws/1.1/"
        // the iphone app's id: its anonymous tokens work (the desktop app's come back all zeros now)
        private const val IOS = "mac-ios-v2.0"
        private const val IPAD = "mac-ios-ipad-v1.0"
        // tokens from the android app (debug info "[App]: com.musixmatch.android…") only work with this
        private const val ANDROID = "android-player-v1.0"

        /** which app the pasted debug info came from */
        fun appIdFor(raw: String): String = when {
            Regex("""\[App]:\s*com\.musixmatch\.android""").containsMatchIn(raw) || "[OSVersion]" in raw && "iPhone" !in raw -> ANDROID
            Regex("""\[Device]:\s*iPad""").containsMatchIn(raw) -> IPAD
            else -> IOS
        }

        /** "[UserToken]: 2610…" from the app's debug info, or the bare 54-character token */
        fun parseToken(raw: String): String? {
            Regex("""\[UserToken]:\s*([a-f0-9]+)""").find(raw)?.let { return it.groupValues[1] }
            return raw.trim().takeIf { Regex("""^[a-f0-9]{40,64}$""").matches(it) }
        }
    }
}
