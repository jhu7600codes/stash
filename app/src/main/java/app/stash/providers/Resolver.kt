package app.stash.providers

import android.util.Log
import app.stash.data.db.Track
import app.stash.data.db.TrackDao
import app.stash.data.prefs.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max

/** every provider, in fallback order */
@Singleton
class ProviderRegistry @Inject constructor(
    youtube: YouTubeProvider,
    soundcloud: SoundCloudProvider,
    vocadb: VocaDbProvider,
    bandcamp: BandcampProvider,
    audius: AudiusProvider,
    jamendo: JamendoProvider,
    archive: InternetArchiveProvider,
    fma: FreeMusicArchiveProvider,
    private val settings: Settings,
) {
    val all: List<MusicProvider> = listOf(youtube, vocadb, soundcloud, audius, bandcamp, jamendo, archive, fma)

    fun get(id: String): MusicProvider? = all.firstOrNull { it.id == id }

    /** enabled and configured, in fallback order */
    suspend fun active(): List<MusicProvider> {
        val disabled = settings.current().disabledProviders
        return all.filter { it.id !in disabled && it.isConfigured() }
    }
}

object Matcher {
    // ascii and fullwidth round brackets / square brackets / 【】 (「」 often hold the title itself)
    private val brackets = Regex("""\s*[(\[（【][^)\]）】]*[)\]）】]""")
    private val feat = Regex("""\s*\b(feat\.?|ft\.?|featuring|with)(\s+|(?<=\.)).*$""", RegexOption.IGNORE_CASE)
    private val nonWord = Regex("""[^\p{L}\p{N}]+""")
    private val marks = Regex("\\p{Mn}+")

    /**
     * lowercase, no accents, cyrillic and japanese kana spelled in latin letters (so "Земфира" ==
     * "Zemfira" and "タカオカミズキ" == "takaokamizuki"), no brackets / feat. / punctuation.
     * kanji, kana and latin runs are split into separate words, since japanese has no spaces.
     * a title that's only symbols (like "∞") is kept as those symbols instead of turning empty.
     */
    fun normalize(s: String): String = words(s, stripExtras = true)

    private fun words(s: String, stripExtras: Boolean): String {
        var t = romanizeKana(splitScripts(s.lowercase()))
        t = transliterate(t)
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replace(marks, "")
        if (stripExtras) t = t.replace(brackets, " ").replace(feat, "")
        val clean = t.replace(nonWord, " ").trim()
        return clean.ifEmpty { s.filterNot { it.isWhitespace() } }
    }

    private val cyrillic = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh",
        'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
        'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts",
        'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu",
        'я' to "ya", 'і' to "i", 'ї' to "yi", 'є' to "ye", 'ґ' to "g", 'ў' to "u",
    )

    fun transliterate(s: String): String {
        if (s.none { it in cyrillic }) return s
        val out = StringBuilder(s.length + 8)
        for (c in s) out.append(cyrillic[c] ?: c)
        return out.toString()
    }

    private enum class Script { HAN, KANA, OTHER }

    private fun scriptOf(c: Char): Script = when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.HAN -> Script.HAN
        Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> Script.KANA
        else -> Script.OTHER
    }

    /** "おちゃめ機能" -> "おちゃめ 機能", "テトsv" -> "テト sv" */
    private fun splitScripts(s: String): String {
        if (s.none { scriptOf(it) != Script.OTHER }) return s
        val out = StringBuilder(s.length + 8)
        var prev: Script? = null
        for (c in s) {
            val cur = if (c.isLetterOrDigit() || c == 'ー') scriptOf(c) else null
            if (cur != null && prev != null && cur != prev) out.append(' ')
            out.append(c)
            prev = cur
        }
        return out.toString()
    }

    // hepburn, hiragana order; katakana is the same block shifted by 0x60
    private val kana: Map<Char, String> = run {
        val chars = "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをん" +
            "がぎぐげござじずぜぞだぢづでどばびぶべぼぱぴぷぺぽゔ"
        val roma = ("a i u e o ka ki ku ke ko sa shi su se so ta chi tsu te to na ni nu ne no ha hi fu he ho " +
            "ma mi mu me mo ya yu yo ra ri ru re ro wa o n ga gi gu ge go za ji zu ze zo da ji zu de do " +
            "ba bi bu be bo pa pi pu pe po vu").split(' ')
        buildMap {
            chars.forEachIndexed { i, c -> put(c, roma[i]); put(c + 0x60, roma[i]) }
        }
    }
    private val smallY = mapOf('ゃ' to "a", 'ゅ' to "u", 'ょ' to "o", 'ャ' to "a", 'ュ' to "u", 'ョ' to "o")
    private val smallV = mapOf('ぁ' to "a", 'ぃ' to "i", 'ぅ' to "u", 'ぇ' to "e", 'ぉ' to "o", 'ァ' to "a", 'ィ' to "i", 'ゥ' to "u", 'ェ' to "e", 'ォ' to "o")

    fun romanizeKana(s: String): String {
        if (s.none { scriptOf(it) == Script.KANA }) return s
        val out = StringBuilder(s.length * 2)
        var double = false
        for (c in s) {
            val y = smallY[c]
            val v = smallV[c]
            val base = kana[c]
            when {
                // きゃ -> kya, しゃ -> sha, じゃ -> ja
                y != null && out.endsWith("i") -> {
                    out.setLength(out.length - 1)
                    if (!(out.endsWith("sh") || out.endsWith("ch") || out.endsWith("j"))) out.append('y')
                    out.append(y)
                }
                // ファ -> fa, ティ -> ti
                v != null && out.length >= 2 && out.last() in "aiueo" -> {
                    out.setLength(out.length - 1)
                    out.append(v)
                }
                c == 'っ' || c == 'ッ' -> double = true
                c == 'ー' -> Unit
                base != null -> {
                    if (double && base[0] !in "aiueon") out.append(if (base.startsWith("ch")) 't' else base[0])
                    double = false
                    out.append(base)
                }
                y != null -> out.append("y").append(y)
                v != null -> out.append(v)
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** looser form for comparing: spellings like kh/h, iy/y, yo/e, ou/o treated the same */
    private fun fold(t: String) = t.replace("kh", "h").replace("iy", "y").replace("yo", "e").replace("ii", "i")
        .replace("ou", "o").replace("uu", "u")

    private fun tokens(s: String, stripExtras: Boolean = true) =
        words(s, stripExtras).split(' ').filter { it.isNotEmpty() }.map(::fold).toSet()

    private fun hasLetters(s: String) = s.any { it.isLetterOrDigit() }

    /** "covered" counts for "cover", "r4ns0m" for "r4ns0m": equal, or one starts the other (4+ letters) */
    private fun Set<String>.has(word: String) =
        word in this || (word.length >= 4 && any { it.length >= 4 && (it.startsWith(word) || word.startsWith(it) || typo(it, word)) })

    /**
     * two letters swapped, or one missing / extra, for words of 6+ letters ("illeagl" = "illegal").
     * a changed letter doesn't count: "light" and "night" are different songs.
     */
    private fun typo(a: String, b: String): Boolean {
        if (a.length < 6 || b.length < 6 || abs(a.length - b.length) > 1) return false
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 2
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length] <= 1
    }

    private fun share(want: Set<String>, have: Set<String>): Double =
        if (want.isEmpty()) 0.0 else want.count { have.has(it) }.toDouble() / want.size

    /** titles made only of these (and numbers) don't say which song it is */
    private val genericTitleWords = setOf("untitled", "track", "intro", "outro", "interlude", "skit", "smaskifa")

    /** words that mean "not the original recording" */
    private val versionWords = setOf(
        "cover", "remix", "live", "sped", "slowed", "nightcore", "karaoke", "piano", "instrumental",
        "reverb", "8d", "mashup", "acoustic", "tutorial", "reaction", "extended", "loop",
        // rhythm-game charts, game ports and edits: the song is in there, but not as itself
        "port", "ports", "osu", "ddr", "flowmove", "gameplay", "chart", "flip", "bootleg", "hour", "hours",
        "rhythia", "arrange", "arranged", "attempt", "acc", "mmd", "hyper",
    )

    /** covers / playthroughs, written the japanese way or as phrases the word list can't catch */
    private val versionMarkers = listOf(
        "歌ってみた", "踊ってみた", "弾いてみた", "叩いてみた", "演奏してみた", "描いてみた", "作ってみた", "カバー", "カラオケ",
        "off vocal", "オフボーカル", "instrumental", "mmd", "guitar cover", "bass cover", "drum cover", "violin", "ピアノ",
        "ギター", "ベース", "dance practice", "midi", "8-bit", "music box", "オルゴール", "sped up", "mashup", "マッシュアップ",
        "tutorial", "piano cover", "歌枠", "耐久", "替え歌", "sm64", "pump it up", "beat saber", "fnf", "challenge", "lv.",
    )

    /** words in album names that say nothing about who made it */
    private val genericAlbumWords = setOf(
        "original", "game", "soundtrack", "ost", "vol", "volume", "deluxe", "edition", "the", "a", "of",
        "remastered", "version", "ep", "single", "music", "from", "motion", "picture", "series", "part",
        // playlist names count too (see Resolver.match's context), so their filler words as well
        "roblox", "themes", "theme", "your", "my", "all", "time", "top", "songs", "song", "playlist", "mix",
        "best", "liked", "favorites", "favourites", "tracks", "gaming", "and", "for", "in", "on",
    )

    /**
     * a 3-5 letter word of [short] that's the initials of words next to each other in [long]:
     * "UTG" and "untitled tag game", "PJSK" and "project sekai…" style abbreviations
     */
    fun acronymMatch(short: String, long: String): Boolean {
        val abbreviations = words(short, false).split(' ').filter { it.length in 3..5 && it.all(Char::isLetter) }.toSet()
        if (abbreviations.isEmpty()) return false
        val seq = words(long, false).split(' ').filter { it.isNotEmpty() }
        return abbreviations.any { a ->
            seq.indices.any { i -> i + a.length <= seq.size && (0 until a.length).all { k -> seq[i + k].first() == a[k] } }
        }
    }

    /** the words of [name] as written (not romanized), keeping only [keep]'s ones, for searching */
    fun originalWords(name: String, keep: Set<String>): String =
        name.split(Regex("""\s+""")).filter { w -> tokens(w).any { it in keep } }.joinToString(" ")

    /** distinctive words of an album / playlist name: no filler, no bare numbers */
    fun distinctiveWords(name: String): Set<String> =
        tokens(name).filter { it !in genericAlbumWords && !it.all(Char::isDigit) }.toSet()

    /**
     * 0..1 for how likely [candidate] is the song "[artist] - [title]" of [durationMs].
     * Titles carry the most weight; any credited artist counts, and it can also show up in the
     * channel or raw upload title ([extra], e.g. "DOORS OST VOL. 1 - Elevator Jam" uploaded by
     * LSPLASH). A close duration is a strong signal that it's not a live/extended cut. A
     * distinctive album word (the game "Doors") counts as half an artist, for soundtracks
     * re-uploaded by fan channels.
     */
    fun score(
        candidate: Track,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        extra: String? = null,
    ): Double {
        val wantTitle = tokens(title)
        val credited = artist.split(',', '&', ';', '、').map { tokens(it) }.filter { it.isNotEmpty() }
        val haveTitle = tokens(candidate.title)
        val haveArtist = tokens(candidate.artist)
        // raw text keeps what's in brackets, like "(@LSPLASH)" or "【初音ミク】"
        val raw = tokens(candidate.title, stripExtras = false) + extra.orEmpty().let { tokens(it, stripExtras = false) }
        val haveAll = haveTitle + haveArtist + raw

        val titleScore = share(wantTitle, haveAll)
        val wantArtistWords = credited.flatten().toSet()
        val extraWords = (haveTitle - wantTitle - wantArtistWords).size // words like "live", "remix", "cover"
        // the best credited artist counts most, but having all of them breaks ties: "wowaka feat.
        // Hatsune Miku" beats someone else's remake that's also sung by miku
        val perArtist = credited.map { a -> maxOf(share(a, haveArtist), share(a, haveAll)) }
        var artistScore = if (perArtist.isEmpty()) 0.0 else perArtist.max() * 0.75 + perArtist.average() * 0.25
        var onlyAlbum = false
        if (artistScore == 0.0 && album != null) {
            val albumWords = distinctiveWords(album) - wantTitle
            val text = candidate.title + " " + candidate.artist + " " + extra.orEmpty()
            // "utg" in the playlist name finds "untitled tag game" uploads, and the other way round
            val shortHit = acronymMatch(album, text) || acronymMatch(text, album)
            if (shortHit || albumWords.isNotEmpty() && share(albumWords, haveAll) >= 0.5) {
                artistScore = 0.5
                onlyAlbum = true
            }
        }
        // a title with no letters at all ("∞") can't be matched by words: go by artist + length
        if (!hasLetters(title)) {
            val sameSymbols = candidate.title.contains(title.trim())
            val lengthScore = if (durationMs > 0 && candidate.durationMs > 0) {
                val diff = abs(durationMs - candidate.durationMs) / 1000
                when { diff <= 3 -> 0.45; diff <= 10 -> 0.35; diff <= 25 -> 0.15; else -> 0.0 }
            } else 0.0
            return (artistScore * 0.45 + lengthScore + if (sameSymbols) 0.3 else 0.0).coerceIn(0.0, 1.0)
        }
        var s = titleScore * 0.55 + artistScore * 0.3 - minOf(extraWords, 4) * 0.03
        // "metal cover", "sped up", "piano" etc. that the wanted title doesn't have: a different version
        val rawText = (candidate.title + " " + extra.orEmpty()).lowercase()
        // "covered" counts as "cover", "remixed" as "remix"
        val otherVersion = raw.any { w -> versionWords.any { v -> w == v || (v.length >= 4 && w.startsWith(v)) } && !wantTitle.has(w) } ||
            versionMarkers.any { it in rawText && it !in title.lowercase() }
        // big enough that a perfect cover / chart video still loses to the plain song
        if (otherVersion) s -= 0.35
        val diff = if (durationMs > 0 && candidate.durationMs > 0) abs(durationMs - candidate.durationMs) / 1000 else null
        // an album word alone is weak evidence: it also needs the length to line up
        if (onlyAlbum && (diff == null || diff > 8)) s = minOf(s, 0.5)
        if (diff != null) {
            s += when {
                diff <= 3 -> 0.15
                diff <= 8 -> 0.1
                diff <= 20 -> 0.0
                diff <= 60 -> -0.2
                // a minute or more off: a different cut, a loop, a medley
                else -> -0.35
            }
        } else {
            s += 0.05
        }
        // "Untitled #1", "Track 3", "Intro": a title that names nothing (sigur rós has a whole album of
        // untitleds, smáskífa comes in several cuts). only the length can tell which one it is
        val generic = wantTitle.isNotEmpty() && wantTitle.all { it in genericTitleWords || it.all(Char::isDigit) }
        if (generic && (diff == null || diff > 4)) s = minOf(s, 0.5)
        // right title by a different artist is usually a cover, not the song...
        if (credited.isNotEmpty() && artistScore == 0.0) {
            // ...unless the whole title is there, the length is within 3 seconds and nothing says
            // it's another version: originals reuploaded by other channels (vocaloid songs on
            // fan channels, "巡音ルカ「ルカルカ★ナイトフィーバー」" with no "samfree" in it).
            // just enough to pass, so any upload naming the artist still wins
            val reupload = titleScore >= 1.0 && wantTitle.sumOf { it.length } >= 6 && diff != null && diff <= 3 && !otherVersion
            s = if (reupload) ACCEPT else minOf(s, 0.4)
        }
        return s.coerceIn(0.0, 1.0)
    }

    /** the title as typed, minus "(feat. …)", "[Remastered]" and " - Cover" style tails */
    fun stripForSearch(title: String): String =
        title.replace(brackets, " ").replace(feat, "")
            // "Song - Remastered 2011", but not "Untitled #1 - Vaka" (there the tail is the name)
            .replace(Regex("""\s+[-–]\s+.*$""")) { m ->
                if (Regex("""^\s*untitled\s*#?\s*\d+\s*$""", RegexOption.IGNORE_CASE).matches(title.substring(0, m.range.first))) m.value else ""
            }
            .replace(Regex("""\s*[-~～][^-~～]+[-~～]\s*$"""), "") // "ルカルカ★ナイトフィーバー -Miku ver.-"
            .trim().ifEmpty { title }

    /** "LSPLASH, jasper.creations" -> "LSPLASH": searching with every credit buries the song */
    fun primaryArtist(artist: String) = artist.split(',', '&', ';', '、').first().trim()

    const val ACCEPT = 0.6
}

/**
 * Picks where a track actually plays from: the downloaded file first, then the track's own
 * provider, then whichever fallback worked last time, then a fresh search through every other
 * provider in order. Whatever works is remembered on the track.
 */
@Singleton
class Resolver @Inject constructor(
    private val registry: ProviderRegistry,
    private val dao: TrackDao,
) {
    /** [at]: until when the link can be used */
    private data class Cached(val source: StreamSource, val at: Long)

    private fun validUntil(url: String): Long {
        val now = System.currentTimeMillis()
        val expire = Regex("""[?&/]expire[=/](\d{9,11})""").find(url)?.groupValues?.get(1)?.toLongOrNull()
            ?: return now + CACHE_MS
        return (expire * 1000 - 10 * 60 * 1000L).coerceIn(now, now + 5 * 60 * 60 * 1000L)
    }

    /**
     * Stream urls are only memoised for a few minutes so seeking doesn't re-run yt-dlp
     * every time. They're never written anywhere, they expire within hours.
     */
    private val shortCache = ConcurrentHashMap<String, Cached>()
    private val inFlight = ConcurrentHashMap<String, Deferred<StreamSource>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun invalidate(trackId: String) {
        shortCache.remove(trackId)
    }

    /** songs whose lookup just failed, so the player's own quick retries don't each start over */
    private val failedAt = ConcurrentHashMap<String, Pair<Long, String>>()

    /** whether the stream last found for this song is hls; null if none is cached */
    fun cachedHls(trackId: String): Boolean? = shortCache[trackId]?.source?.hls

    /** the stream broke mid-song: next time get this song through yt-dlp, the slow sure way */
    suspend fun retrySlow(trackId: String) {
        shortCache.remove(trackId)
        failedAt.remove(trackId)
        val track = dao.get(trackId) ?: return
        val yt = registry.get("youtube") as? YouTubeProvider ?: return
        listOfNotNull(track.primaryLocator, track.fallbackLocator)
            .filter { it.provider == "youtube" }
            .forEach { yt.preferYtDlp(it.sourceId) }
    }

    /** the youtube video for a song (its own, the one it was matched to, or a search), for the webview player */
    suspend fun youtubeIdFor(trackId: String): String? {
        val track = dao.get(trackId) ?: return null
        val idIn = { s: String -> Regex("""(?:[?&]v=|youtu\.be/)([A-Za-z0-9_-]{11})""").find(s)?.groupValues?.get(1) }
        listOfNotNull(track.fallbackLocator, track.primaryLocator).firstOrNull { it.provider == "youtube" }
            ?.let { idIn(it.sourceId) }?.let { return it }
        val yt = registry.get("youtube") ?: return null
        return runCatching {
            kotlinx.coroutines.withTimeout(SEARCH_MS) { bestMatch(yt, track.title, track.artist, track.durationMs) }
        }.getOrNull()?.let { idIn(it.sourceId) }
    }

    /** the last songs looked up and how long it took, newest first (settings → playback log) */
    data class LogEntry(val title: String, val via: String, val ms: Long, val error: String?)

    private val _log = kotlinx.coroutines.flow.MutableStateFlow<List<LogEntry>>(emptyList())
    val log: kotlinx.coroutines.flow.StateFlow<List<LogEntry>> = _log

    /** a song actually started making sound, [ms] after the player switched to it */
    fun recordPlaying(title: String, ms: Long) = record(LogEntry(title, "▶ playing", ms, null))

    /** the player itself choked on a stream it was given (403, unsupported format…) */
    fun recordPlayerError(title: String, what: String) = record(LogEntry(title, "player error", 0, what))

    private fun record(e: LogEntry) {
        _log.value = (listOf(e) + _log.value).take(30)
    }

    /** [ahead]: looked up for an upcoming song, not one that's playing (marked so in the log) */
    suspend fun streamFor(trackId: String, ahead: Boolean = false): StreamSource {
        val track = dao.get(trackId) ?: throw ProviderException("Unknown song $trackId")
        track.localPath?.let { path ->
            val f = File(path)
            if (f.exists() && f.length() > 0) return StreamSource(f.toURI().toString(), via = "downloaded")
        }
        shortCache[trackId]?.let { if (System.currentTimeMillis() < it.at) return it.source }
        // the player retries a failed load on its own, a few times; without this each retry started
        // the whole search over (up to 90s each), which is how a song could take minutes to fail
        failedAt[trackId]?.let { (at, why) ->
            if (System.currentTimeMillis() - at < FAIL_MEMORY_MS) throw ProviderException(why)
        }
        val started = System.currentTimeMillis()
        return try {
            lookUp(trackId, track).also {
                val via = it.via.ifEmpty { track.provider } + if (ahead) " · ready ahead" else ""
                record(LogEntry(track.title, via, System.currentTimeMillis() - started, null))
            }
        } catch (e: Exception) {
            // cancelled (skipped, or the player gave up on that load) isn't a failure
            if (e is kotlinx.coroutines.CancellationException) throw e
            record(LogEntry(track.title, "failed", System.currentTimeMillis() - started, e.message))
            if (e !is kotlinx.coroutines.CancellationException) failedAt[trackId] = System.currentTimeMillis() to (e.message ?: "Couldn't play this")
            throw e
        }
    }

    private suspend fun lookUp(trackId: String, track: Track): StreamSource {

        // the prefetch and exoplayer can ask for the same song at once; share one lookup
        val job = inFlight.computeIfAbsent(trackId) {
            scope.async { resolveWithFallback(track) { provider, loc -> provider.resolveStream(loc) } }
        }
        val source = try {
            // never spin forever: after a minute the player shows why instead of loading
            kotlinx.coroutines.withTimeoutOrNull(OVERALL_MS) { job.await() }
                ?: throw ProviderException("Took too long to find this song")
        } finally {
            inFlight.remove(trackId, job)
        }
        // kept until shortly before youtube's link expires (its "expire=" stamp, usually ~6 hours),
        // so a song played again, or got ready ahead, never waits on yt-dlp twice
        shortCache[trackId] = Cached(source, validUntil(source.url))
        return source
    }

    suspend fun downloadSourceFor(track: Track): DownloadSource =
        resolveWithFallback(track) { provider, loc -> provider.downloadSource(loc) }

    private suspend fun <T> resolveWithFallback(track: Track, op: suspend (MusicProvider, Locator) -> T): T {
        val errors = mutableListOf<String>()
        val tried = mutableSetOf<String>()

        for (loc in listOfNotNull(track.fallbackLocator, track.primaryLocator)) {
            val provider = registry.get(loc.provider) ?: continue
            tried += loc.provider
            try {
                return kotlinx.coroutines.withTimeout(STEP_MS) { op(provider, loc) }
            } catch (e: Exception) {
                Log.w(TAG, "${provider.id} failed for ${track.title}", e)
                errors += "${provider.displayName}: ${e.message}"
            }
        }

        for (provider in registry.active()) {
            if (provider.id in tried) continue
            val match = runCatching {
                kotlinx.coroutines.withTimeout(SEARCH_MS) { bestMatch(provider, track.title, track.artist, track.durationMs) }
            }.getOrNull() ?: continue
            try {
                val result = kotlinx.coroutines.withTimeout(STEP_MS) { op(provider, match.primaryLocator) }
                dao.setResolved(track.id, match.provider, match.sourceId, match.streamHint)
                return result
            } catch (e: Exception) {
                errors += "${provider.displayName}: ${e.message}"
            }
        }
        throw ProviderException(errors.lastOrNull() ?: "Nothing could play this")
    }

    private suspend fun bestMatch(provider: MusicProvider, title: String, artist: String, durationMs: Long): Track? =
        pick(provider.search("${Matcher.primaryArtist(artist)} $title".trim(), 8).map { SearchHit(it) }, title, artist, durationMs, null)

    private fun pick(hits: List<SearchHit>, title: String, artist: String, durationMs: Long, album: String?): Track? =
        // a youtube music "song" (the label's own audio) beats an equally good video upload
        hits.map { h -> h.track to Matcher.score(h.track, title, artist, durationMs, album, h.byline ?: h.uploader?.name) + if (h.official) 0.05 else 0.0 }
            .filter { it.second >= Matcher.ACCEPT }
            .maxByOrNull { it.second }?.first

    /**
     * finds a song from another service (an imported playlist) on the first provider that has it.
     * first "artist title" everywhere; then, for soundtracks and fan uploads, "title album" on
     * youtube and soundcloud ("R4NS0M Doors" finds what "LSPLASH R4NS0M" doesn't).
     */
    /**
     * [context] is the playlist the song came from: game soundtracks and roblox music are often
     * uploaded under the game's name ("untitled tag game OST: B0MB!!") rather than the artist's,
     * so the playlist name is searched with and counts like an album word.
     */
    suspend fun match(
        title: String, artist: String, durationMs: Long, album: String? = null, context: String? = null, loose: Boolean = false,
    ): Track? {
        val seen = mutableListOf<SearchHit>()
        val hit = matchStrict(title, artist, durationMs, album, context, seen)
        if (hit != null || !loose) return hit
        // a rescan, so everything above already failed once: the same candidates, ignoring the
        // length (the spotify version is often a different cut than the youtube upload). the
        // title and the artist (or the playlist's game) still have to be there
        val hint = listOfNotNull(album, context).joinToString(" ").ifBlank { null }
        return seen.distinctBy { it.track.id }
            .map { h -> h.track to Matcher.score(h.track, title, artist, 0, hint, h.byline ?: h.uploader?.name) + if (h.official) 0.05 else 0.0 }
            .filter { it.second >= Matcher.ACCEPT }
            .maxByOrNull { it.second }?.first
    }

    private suspend fun matchStrict(
        title: String, artist: String, durationMs: Long, album: String?, context: String?, seen: MutableList<SearchHit>,
    ): Track? {
        val providers = registry.active()
        val hint = listOfNotNull(album, context).joinToString(" ").ifBlank { null }
        val main = "${Matcher.primaryArtist(artist)} $title".trim()
        for (provider in providers) {
            val hit = runCatching { pick(provider.matchCandidates(main, title).also { seen += it }, title, artist, durationMs, hint) }
                .onFailure { Log.w(TAG, "match via ${provider.id} failed", it) }
                .getOrNull()
            if (hit != null) return hit
        }
        // the title alone, without decorations: songs whose uploads don't name the artist
        val bare = Matcher.stripForSearch(title)
        // (vocadb already searched by the bare title above)
        for (provider in providers.filter { it.id == "youtube" || it.id == "soundcloud" }) {
            val hit = runCatching { pick(provider.matchCandidates(bare, bare).also { seen += it }, title, artist, durationMs, hint) }.getOrNull()
            if (hit != null) return hit
        }
        val albumWords = album?.replace(Regex("""\s*[(\[].*?[)\]]"""), "")?.substringBefore(" - ")?.trim()
        if (!albumWords.isNullOrBlank() && !albumWords.equals(title, ignoreCase = true)) {
            for (provider in providers.filter { it.id == "youtube" || it.id == "soundcloud" }) {
                val hit = runCatching { pick(provider.matchCandidates("$title $albumWords").also { seen += it }, title, artist, durationMs, hint) }
                    .getOrNull()
                if (hit != null) return hit
            }
        }
        // the playlist's own name, for soundtracks uploaded under the game's name
        val contextWords = context?.let { Matcher.distinctiveWords(it) }.orEmpty()
        if (contextWords.isNotEmpty()) {
            val q = "$bare ${Matcher.originalWords(context!!, contextWords)}"
            for (provider in providers.filter { it.id == "youtube" || it.id == "soundcloud" }) {
                val hit = runCatching { pick(provider.matchCandidates(q).also { seen += it }, title, artist, durationMs, hint) }.getOrNull()
                if (hit != null) return hit
            }
        }
        return null
    }

    companion object {
        private const val TAG = "Resolver"
        private const val CACHE_MS = 15 * 60 * 1000L
        // yt-dlp alone takes 35-45s on some phones; cutting it at 30s failed songs that would play
        private const val OVERALL_MS = 90_000L
        private const val STEP_MS = 60_000L
        private const val SEARCH_MS = 10_000L
        private const val FAIL_MEMORY_MS = 60_000L
    }
}
