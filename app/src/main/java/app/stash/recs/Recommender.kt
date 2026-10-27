package app.stash.recs

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import app.stash.data.db.Playlist
import app.stash.data.db.PlaylistDao
import app.stash.data.db.PlaylistTrack
import app.stash.data.db.StashDatabase
import app.stash.data.db.Track
import app.stash.data.db.TrackDao
import app.stash.providers.Matcher
import app.stash.providers.YouTubeMusicSearch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * everything "made for you": radios, daily mixes, discover weekly, repeat rewind. it all runs on
 * the phone: your plays and likes pick the seeds, youtube music's radio supplies similar songs.
 */
@Singleton
class Recommender @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val music: YouTubeMusicSearch,
    private val db: StashDatabase,
    private val tracks: TrackDao,
    private val playlists: PlaylistDao,
    private val genres: Genres,
) {
    private data class CachedRadio(val at: Long, val tracks: List<Track>)
    private val radios = ConcurrentHashMap<String, CachedRadio>()
    private val mixLock = Mutex()

    /** similar songs to [seed], youtube music's radio for it (cached an hour) */
    suspend fun radioFor(seed: Track): List<Track> {
        radios[seed.id]?.let { if (System.currentTimeMillis() - it.at < RADIO_CACHE_MS) return it.tracks }
        val videoId = videoIdFor(seed) ?: return emptyList()
        val list = music.radio(videoId)
        radios[seed.id] = CachedRadio(System.currentTimeMillis(), list)
        return list
    }

    /** songs like your latest like that you don't have yet, for the home row */
    suspend fun becauseYouLiked(): Pair<Track, List<Track>>? {
        val seed = tracks.lastLiked() ?: tracks.taste(1).firstOrNull() ?: return null
        val known = knownKeys()
        val recs = runCatching { radioFor(seed) }.getOrDefault(emptyList())
            .filter { key(it) !in known }
            .distinctBy { firstArtist(it.artist) }
            .take(15)
        return if (recs.isEmpty()) null else seed to recs
    }

    /** makes the mixes if they're missing or old: daily mixes once a day, discover once a week */
    suspend fun refreshMixes(forceIt: Boolean = false) = mixLock.withLock {
        val taste = tracks.taste()
        if (taste.size < MIN_TASTE) return@withLock
        // once after the update that added drawn mix covers, so every mix gets its artist photo
        val prefs = context.getSharedPreferences("stash_mixes", Context.MODE_PRIVATE)
        val force = forceIt || prefs.getInt("art", 0) < 1
        prefs.edit().putInt("art", 1).apply()
        val existing = playlists.mixes().associateBy { it.originId }
        val today = startOfDay()
        if (force || (existing["daily-1"]?.updatedAt ?: 0) < today) {
            runCatching { dailyMixes(taste, existing) }.onFailure { Log.w(TAG, "daily mixes failed", it) }
            runCatching { genreMixes(taste, existing) }.onFailure { Log.w(TAG, "genre mixes failed", it) }
            runCatching { artistMixes(taste, existing) }.onFailure { Log.w(TAG, "artist mixes failed", it) }
        }
        if (force || (existing["discover"]?.updatedAt ?: 0) < startOfWeek()) {
            runCatching { discover(taste) }.onFailure { Log.w(TAG, "discover failed", it) }
        }
        runCatching { rewind() }.onFailure { Log.w(TAG, "rewind failed", it) }
    }

    private suspend fun dailyMixes(taste: List<Track>, existing: Map<String?, Playlist>) = coroutineScope {
        val known = knownKeys()
        // your top artists, by how much you play / like them
        val byArtist = taste.groupBy { firstArtist(it.artist) }.filterKeys { it.isNotBlank() }
        val topArtists = byArtist.entries
            .sortedByDescending { (_, list) -> list.sumOf { it.playCount * 2 + (if (it.liked) 5 else 0) + 1 } }
            .take(MAX_DAILY)
        val random = Random(startOfDay())
        val made = topArtists.mapIndexed { i, (artist, own) ->
            async {
                val seed = own.first()
                val radio = runCatching { radioFor(seed) }.getOrDefault(emptyList())
                val radioArtists = radio.map { firstArtist(it.artist) }.toSet()
                // familiar: this artist plus your songs by artists the radio thinks are similar
                val familiar = (own.take(10) + taste.filter { firstArtist(it.artist) in radioArtists && firstArtist(it.artist) != artist }.take(8))
                    .distinctBy { it.id }.shuffled(random)
                val fresh = radio.filter { key(it) !in known }.capPerArtist(2).take(15)
                val mix = interleave(familiar, fresh).take(30)
                val names = mix.map { firstArtist(it.artist) }.distinct().take(3)
                val photo = genres.picture(artist)
                Triple("daily-${i + 1}", mix, "${names.joinToString(", ")} and more" to photo)
            }
        }.awaitAll()
        made.forEachIndexed { i, (id, mix, about) ->
            if (mix.size >= 5) save(id, "Daily Mix ${i + 1}", about.first, mix, about.second)
        }
        // fewer top artists than before: drop the leftover mixes
        existing.keys.filterNotNull().filter { it.startsWith("daily-") && made.none { m -> m.first == it } }
            .forEach { id -> existing[id]?.let { playlists.deleteFully(it.id) } }
    }

    /** your artists ranked by plays and likes, with their songs */
    private fun topArtists(taste: List<Track>, n: Int): List<Pair<String, List<Track>>> =
        taste.groupBy { firstArtist(it.artist) }.filterKeys { it.isNotBlank() }.entries
            .map { (artist, list) -> artist to list }
            .sortedByDescending { (_, list) -> list.sumOf { it.playCount * 2 + (if (it.liked) 5 else 0) + 1 } }
            .take(n)

    /**
     * one mix per genre you listen to most: your favourite artists from that genre plus
     * similar songs from their radios, labelled "For you by Stash".
     */
    private suspend fun genreMixes(taste: List<Track>, existing: Map<String?, Playlist>) = coroutineScope {
        val known = knownKeys()
        val random = Random(startOfDay() + 7)
        val artists = topArtists(taste, 40)
        val gate = Semaphore(4)
        val withGenre = artists.map { (artist, own) ->
            async { gate.withPermit { Triple(artist, own, runCatching { genres.of(artist) }.getOrNull()) } }
        }.awaitAll().filter { it.third != null }

        val topGenres = withGenre.groupBy { it.third!! }.entries
            .filter { (_, list) -> list.size >= 2 || list.sumOf { it.second.size } >= 6 }
            .sortedByDescending { (_, list) -> list.sumOf { it.second.size } }
            .take(MAX_GENRES)

        val made = topGenres.map { (genre, list) ->
            async {
                val own = list.take(6).flatMap { it.second.take(6) }.shuffled(random).take(15)
                val fresh = list.take(2).map { (_, songs, _) ->
                    async { runCatching { radioFor(songs.first()) }.getOrDefault(emptyList()) }
                }.awaitAll().flatten().filter { key(it) !in known }.distinctBy { it.id }.capPerArtist(2).shuffled(random).take(15)
                val names = list.take(3).joinToString(", ") { it.first }
                val photo = genres.picture(list.first().first)
                Triple("genre-${genre.lowercase()}", genre, Triple(interleave(own, fresh).take(30), "For you by Stash · $names and more", photo))
            }
        }.awaitAll()
        made.forEach { (id, name, mix) -> if (mix.first.size >= 5) save(id, name, mix.second, mix.first, mix.third) }
        dropStale(existing, "genre-", made.map { it.first })
    }

    /** "<artist> Mix": the artist's songs (yours and their popular ones) mixed with similar artists */
    private suspend fun artistMixes(taste: List<Track>, existing: Map<String?, Playlist>) = coroutineScope {
        val known = knownKeys()
        val random = Random(startOfDay() + 13)
        val made = topArtists(taste, MAX_ARTIST_MIXES).map { (artist, own) ->
            async {
                val popular = runCatching { music.search(artist, 15) }.getOrDefault(emptyList())
                    .filter { firstArtist(it.artist).equals(artist, ignoreCase = true) }
                val theirs = (own + popular).distinctBy { key(it) }.shuffled(random).take(18)
                val similar = runCatching { radioFor(own.first()) }.getOrDefault(emptyList())
                    .filter { key(it) !in known && !firstArtist(it.artist).equals(artist, ignoreCase = true) }
                    .capPerArtist(1).take(12)
                Triple("artist-${artist.lowercase()}", "$artist Mix", interleave(theirs, similar).take(30) to genres.picture(artist))
            }
        }.awaitAll()
        made.forEach { (id, name, mix) ->
            if (mix.first.size >= 5) save(id, name, "${name.removeSuffix(" Mix")} and similar artists", mix.first, mix.second)
        }
        dropStale(existing, "artist-", made.map { it.first })
    }

    private suspend fun dropStale(existing: Map<String?, Playlist>, prefix: String, keep: List<String>) {
        existing.keys.filterNotNull().filter { it.startsWith(prefix) && it !in keep }
            .forEach { id -> existing[id]?.let { playlists.deleteFully(it.id) } }
    }

    private suspend fun discover(taste: List<Track>) = coroutineScope {
        val known = knownKeys()
        val random = Random(startOfWeek())
        val seeds = taste.take(20).distinctBy { firstArtist(it.artist) }.shuffled(random).take(5)
        val pool = seeds.map { s -> async { runCatching { radioFor(s) }.getOrDefault(emptyList()) } }.awaitAll().flatten()
        // songs several of your radios agree on come first
        val counted = pool.filter { key(it) !in known }.groupBy { key(it) }
        val picks = counted.values.sortedWith(compareByDescending<List<Track>> { it.size }.thenBy { random.nextInt() })
            .map { it.first() }
            .capPerArtist(2)
            .take(30)
            .shuffled(random)
        if (picks.size >= 5) save("discover", "Discover Weekly", "New to you, picked from what you play. New every Monday", picks)
    }

    private suspend fun rewind() {
        val old = tracks.forgottenFavorites(System.currentTimeMillis() - 30L * DAY_MS)
        val existing = playlists.findMix("rewind")
        if (old.size >= 8) save("rewind", "Repeat Rewind", "Songs you had on repeat a while ago", old)
        else if (existing != null) playlists.deleteFully(existing.id)
    }

    /** [photo]: the artist picture the mix cover is drawn on (see MixArt); a song cover otherwise */
    private suspend fun save(mixId: String, name: String, description: String, list: List<Track>, photo: String? = null) = db.withTransaction {
        val now = System.currentTimeMillis()
        val cover = photo ?: list.firstNotNullOfOrNull { it.artworkUrl }
        val current = playlists.findMix(mixId)
        val id = if (current == null) {
            playlists.insert(Playlist(name = name, description = description, coverUrl = cover, origin = "mix", originId = mixId))
        } else {
            playlists.update(current.copy(name = name, description = description, coverUrl = cover, updatedAt = now))
            playlists.clearTracks(current.id)
            current.id
        }
        list.forEachIndexed { i, t ->
            tracks.ensure(t)
            playlists.insertTrack(PlaylistTrack(id, t.id, i))
        }
    }

    /** the youtube video behind a track: its own, the fallback that worked, or a quick search */
    private suspend fun videoIdFor(t: Track): String? {
        fun idFrom(url: String?) = url?.let { Regex("""[?&]v=([\w-]{11})""").find(it)?.groupValues?.get(1) }
        if (t.provider == "youtube") idFrom(t.sourceId)?.let { return it }
        if (t.resolvedProvider == "youtube") idFrom(t.resolvedSourceId)?.let { return it }
        val hit = runCatching { music.search("${t.artist} ${t.title}", 3) }.getOrDefault(emptyList())
            .maxByOrNull { Matcher.score(it, t.title, t.artist, t.durationMs) }
        return idFrom(hit?.sourceId)
    }

    private suspend fun knownKeys(): Set<String> = tracks.taste(5000).map(::key).toSet()

    companion object {
        private const val TAG = "Recommender"
        private const val RADIO_CACHE_MS = 60 * 60 * 1000L
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val MAX_DAILY = 4
        private const val MAX_GENRES = 4
        private const val MAX_ARTIST_MIXES = 4
        private const val MIN_TASTE = 5

        fun firstArtist(a: String) = a.split(',', '&', ';').first().trim()

        fun key(t: Track) = Matcher.normalize(firstArtist(t.artist)) + "|" + Matcher.normalize(t.title)

        private fun List<Track>.capPerArtist(max: Int): List<Track> {
            val seen = HashMap<String, Int>()
            return filter { t -> val a = firstArtist(t.artist).lowercase(); seen.merge(a, 1, Int::plus)!! <= max }
        }

        /** a, b, a, b… so a mix alternates between songs you know and new ones */
        fun <T> interleave(a: List<T>, b: List<T>): List<T> {
            val out = ArrayList<T>(a.size + b.size)
            for (i in 0 until maxOf(a.size, b.size)) {
                a.getOrNull(i)?.let(out::add)
                b.getOrNull(i)?.let(out::add)
            }
            return out
        }

        private fun startOfDay(): Long =
            LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        private fun startOfWeek(): Long =
            LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
