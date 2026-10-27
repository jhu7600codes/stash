package app.stash.providers

import app.stash.data.db.Track
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * vocadb.net: the vocaloid / utau / synthv database. every song has its japanese, romaji and
 * english names, every producer and voicebank their kana names, plus links to the original
 * uploads (PVs). songs come out as their youtube PV, so they play and download like any youtube song.
 *
 * - "#teto", "#miku", "#gumi"…: songs sung by that voicebank (every version of it: utau, synthv…)
 * - other #tags ("#vocarock") use vocadb's own tags
 * - matching imports: searches every name of a song, and hands all of them (plus kana artist
 *   names) to the matcher, so "Rolling Girl" finds "ローリンガール"
 */
@Singleton
class VocaDbProvider @Inject constructor(
    private val http: OkHttpClient,
    // a provider (not the instance) so a test can build this without yt-dlp
    private val youtube: javax.inject.Provider<YouTubeProvider>,
) : MusicProvider {
    override val id = "vocadb"
    override val displayName = "VocaDB"

    private suspend fun get(path: String, params: List<Pair<String, String>>): JsonObject {
        val url = "$HOST/api/$path".toHttpUrl().newBuilder()
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        return lenientJson.parseToJsonElement(httpIo { http.getText(url.toString()) }).jsonObject
    }

    private suspend fun songs(params: List<Pair<String, String>>, limit: Int): List<SearchHit> =
        get(
            "songs",
            params + listOf(
                "fields" to "Names,PVs,Artists,Tags", "lang" to "Romaji", "onlyWithPvs" to "true",
                "pvServices" to "Youtube", "maxResults" to limit.coerceAtMost(50).toString(),
            ),
        )["items"].asObjectList().mapNotNull(::hitFrom)

    private fun hitFrom(o: JsonObject): SearchHit? {
        // the original upload if there is one, else any youtube reprint
        val pv = o["pvs"].asObjectList()
            .filter { it.str("service") == "Youtube" && it["disabled"]?.toString() != "true" }
            .sortedBy { if (it.str("pvType") == "Original") 0 else 1 }
            .firstOrNull() ?: return null
        val videoId = pv.str("pvId") ?: return null
        val url = "https://www.youtube.com/watch?v=$videoId"
        val names = o["names"].asObjectList().mapNotNull { it.str("value") }
        val artists = o["artists"].asObjectList().mapNotNull { it.obj("artist") }
        val voicebanks = artists.filter { it.str("artistType") !in NOT_VOICES }.mapNotNull { it.str("name") }
        val track = Track(
            id = Track.makeId("youtube", url),
            title = o.str("name") ?: o.str("defaultName") ?: return null,
            artist = o.str("artistString") ?: "",
            durationMs = ((o.num("lengthSeconds") ?: pv.num("length") ?: 0.0) * 1000).toLong(),
            artworkUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            provider = "youtube",
            sourceId = url,
        )
        val tags = voicebanks.map { it.lowercase() } +
            o["tags"].asObjectList().mapNotNull { it.obj("tag")?.str("name")?.lowercase() }.take(3)
        // every name and every artist spelling, kana included, for the matcher
        val byline = (names + artists.mapNotNull { it.str("name") } + artists.mapNotNull { it.str("additionalNames") } +
            listOfNotNull(pv.str("name"), pv.str("author"))).joinToString(" ")
        return SearchHit(track, plays = null, tags = tags.distinct(), byline = byline)
    }

    /**
     * "teto" -> kasane teto's main voicebank on every engine she has (utau, synthv…), since on
     * vocadb those are separate artists, not versions of one. "teto utau" / "teto synthv" ->
     * only that one. songs are then asked for with childVoicebanks, which adds that voicebank's
     * own appends / updates (SV2, Kyuu…). empty if it isn't a voicebank at all.
     */
    private suspend fun voicebanks(spec: String): List<String> {
        val words = Matcher.normalize(spec).split(' ').filter { it.isNotEmpty() }
        val engine = words.firstNotNullOfOrNull { ENGINES[it] }
        val nameWords = words.filter { it !in ENGINES && it != "v" }.toSet()
        if (nameWords.isEmpty()) return emptyList()
        val found = get(
            "artists",
            listOf(
                "query" to nameWords.joinToString(" "), "nameMatchMode" to "Words", "artistTypes" to (engine ?: VOICE_TYPES),
                "lang" to "Romaji", "sort" to "FollowerCount", "maxResults" to "12",
            ),
        )["items"].asObjectList()
        // "rin" also hits "megurine luka" by word search; keep names that really have the words
        val exact = found.filter { a -> Matcher.normalize(a.str("name").orEmpty()).split(' ').containsAll(nameWords) }
        val picked = exact.ifEmpty { if (nameWords.joinToString(" ") in KNOWN_VOICES) found.take(1) else emptyList() }
        // one per engine, most followed first
        return picked.distinctBy { it.str("artistType") }.take(if (engine != null) 1 else 3)
            .mapNotNull { it.str("id") ?: it.num("id")?.toLong()?.toString() }
    }

    override suspend fun search(query: String, limit: Int) = searchHits(query, limit).map { it.track }

    override suspend fun searchHits(query: String, limit: Int, tag: String?): List<SearchHit> {
        val sort = "sort" to "RatingScore"
        // a voicebank name, as a #tag or typed on its own ("teto", "teto synthv", "hatsune miku")
        val voice = tag ?: query.takeIf { q ->
            val n = Matcher.normalize(q).split(' ').filter { it !in ENGINES }.joinToString(" ")
            n in KNOWN_VOICES
        }
        if (voice != null) {
            val ids = voicebanks(voice)
            if (ids.isNotEmpty()) {
                val perVoice = kotlinx.coroutines.coroutineScope {
                    ids.map { id ->
                        async { runCatching { songs(listOf("artistId[]" to id, "childVoicebanks" to "true", sort), limit) }.getOrDefault(emptyList()) }
                    }.map { it.await() }
                }
                // take turns, so "teto" is utau and synthv songs, not one buried under the other
                val mixed = (0 until (perVoice.maxOfOrNull { it.size } ?: 0)).flatMap { i -> perVoice.mapNotNull { it.getOrNull(i) } }
                return mixed.distinctBy { it.track.id }.take(limit)
            }
            if (tag != null) return songs(listOf("tagName[]" to tag, sort), limit)
        }
        return songs(listOf("query" to query, "nameMatchMode" to "Auto", sort), limit)
    }

    override suspend fun matchCandidates(query: String, title: String?): List<SearchHit> {
        // vocadb searches song names only, so look up the title alone (in any of its spellings)
        val name = title?.let { Matcher.stripForSearch(it) } ?: query
        return songs(listOf("query" to name, "nameMatchMode" to "Auto", "sort" to "RatingScore"), 10)
    }

    // songs are youtube PVs, so these are never asked for vocadb ids; hand them to youtube anyway
    override suspend fun resolveStream(locator: Locator) = youtube.get().resolveStream(locator)
    override suspend fun metadata(sourceId: String) = youtube.get().metadata(sourceId)
    override suspend fun downloadSource(locator: Locator) = youtube.get().downloadSource(locator)

    companion object {
        private const val HOST = "https://vocadb.net"
        private const val VOICE_TYPES =
            "Vocaloid,UTAU,CeVIO,SynthesizerV,NEUTRINO,VoiSona,NewType,Voiceroid,VOICEVOX,ACEVirtualSinger,AIVOICE,OtherVoiceSynthesizer"
        private val NOT_VOICES = setOf("Producer", "Circle", "Label", "OtherGroup", "Illustrator", "Animator", "Lyricist", "OtherIndividual", "CoverArtist", "Band")

        /** words that pick one engine's voicebank ("teto synthv", "miku v4x" stays all-engine) */
        private val ENGINES = mapOf(
            "utau" to "UTAU", "sv" to "SynthesizerV", "synthv" to "SynthesizerV", "synthesizer" to "SynthesizerV",
            "synthesizerv" to "SynthesizerV", "vocaloid" to "Vocaloid", "cevio" to "CeVIO", "voicepeak" to "OtherVoiceSynthesizer",
            "voisona" to "VoiSona", "neutrino" to "NEUTRINO", "voicevox" to "VOICEVOX",
        )

        /** plain searches that mean "songs by this voicebank" (normalized), as typed */
        val KNOWN_VOICES = setOf(
            "miku", "hatsune miku", "teto", "kasane teto", "gumi", "megpoid", "kaito", "meiko", "rin", "len",
            "kagamine rin", "kagamine len", "luka", "megurine luka", "ia", "flower", "v flower", "una", "otomachi una",
            "yukari", "yuzuki yukari", "kafu", "sekai", "rime", "maki", "tsurumaki maki", "zundamon", "kaai yuki",
            "defoko", "utane uta", "momone momo", "fukase", "mayu", "galaco", "akita neru", "neru", "kokone", "lily",
            "gackpoid", "kamui gakupo", "gakupo", "miku append", "frimomen", "kizuna akari", "akari", "ritsu", "namine ritsu",
            "tohoku kiritan", "kiritan", "koharu rikka", "rikka", "natsuki karin", "karin", "chis-a", "ci flower", "ryuusei",
        ).map { Matcher.normalize(it) }.toSet()
    }
}
