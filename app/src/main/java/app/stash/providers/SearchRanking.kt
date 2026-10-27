package app.stash.providers

import app.stash.data.db.Track
import app.stash.recs.Recommender

/** what was typed: plain words, or a #tag ("tag:phonk" works too) */
data class SearchQuery(val text: String, val tag: String?) {
    companion object {
        fun parse(raw: String): SearchQuery {
            val q = raw.trim()
            val tag = when {
                q.startsWith("#") -> q.removePrefix("#")
                q.startsWith("tag:", ignoreCase = true) -> q.substring(4)
                else -> null
            }?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            return SearchQuery(tag ?: q, tag)
        }
    }
}

object SearchRanking {
    /** under this many plays/views counts as underground */
    const val UNDERGROUND_MAX_PLAYS = 200_000L

    /**
     * one list from every source: library hits first, then by how well the song matches (its
     * title, artist and tags), each source's own order breaking ties. duplicates (same artist +
     * title from two sources) are shown once. [underground] drops anything known to be big and
     * puts the least-played first.
     */
    fun merge(
        query: SearchQuery,
        local: List<Track>,
        providerOrder: List<String>,
        results: Map<String, List<SearchHit>>,
        underground: Boolean,
    ): List<SearchHit> {
        val words = Matcher.normalize(query.text).split(' ').filter { it.isNotEmpty() }
        fun relevance(h: SearchHit): Double {
            val t = h.track
            if (query.tag != null) {
                val tag = query.tag
                return when {
                    h.tags.any { it == tag } -> 1.0
                    h.tags.any { it.contains(tag) } -> 0.85
                    Matcher.normalize("${t.artist} ${t.title}").contains(Matcher.normalize(tag)) -> 0.6
                    else -> 0.3
                }
            }
            if (words.isEmpty()) return 0.0
            val text = Matcher.normalize("${t.artist} ${t.title} ${h.tags.joinToString(" ")}")
            val title = Matcher.normalize("${t.artist} ${t.title}")
            // words in the title/artist count fully, words only found in tags count a bit less
            return words.sumOf { w -> if (w in title) 1.0 else if (w in text) 0.7 else 0.0 } / words.size
        }

        val remote = providerOrder.flatMapIndexed { rank, p ->
            results[p].orEmpty().mapIndexed { i, h -> Ranked(h, relevance(h), i, rank) }
        }.filter { !underground || (it.hit.plays ?: 0) <= UNDERGROUND_MAX_PLAYS }

        val sorted = if (underground) {
            remote.sortedWith(
                compareByDescending<Ranked> { (it.relevance * 4).toInt() } // coarse buckets, then smallest first
                    .thenBy { if (it.hit.plays == null) 1 else 0 } // known-small before unknown
                    .thenBy { it.hit.plays ?: Long.MAX_VALUE }
            )
        } else {
            remote.sortedWith(
                compareByDescending<Ranked> { it.relevance }.thenBy { it.index }.thenBy { it.providerRank }
            )
        }
        return (local.map { SearchHit(it) } + sorted.map { it.hit }).distinctBy { Recommender.key(it.track) }
    }

    /**
     * artists: closest name first (the one you typed always shows, however big), then the
     * rest; underground mode drops big ones that don't match the name and prefers small ones
     */
    fun mergeArtists(query: String, artists: List<RemoteArtist>, underground: Boolean): List<RemoteArtist> {
        val q = Matcher.normalize(query).replace(" ", "")
        fun name(a: RemoteArtist) = Matcher.normalize(a.name).replace(" ", "")
        fun closeness(a: RemoteArtist) = when {
            name(a) == q -> 3
            name(a).startsWith(q) || q.startsWith(name(a)) && name(a).length >= 3 -> 2
            name(a).contains(q) -> 1
            else -> 0
        }
        return artists
            .filter { !underground || closeness(it) >= 2 || (it.followers ?: 0) < 50_000 }
            .sortedWith(
                compareByDescending<RemoteArtist> { closeness(it) }
                    .thenBy { if (underground) it.followers ?: 0 else -(it.followers ?: 0) }
            )
            .distinctBy { it.provider + ":" + it.id }
            .take(24)
    }

    private data class Ranked(val hit: SearchHit, val relevance: Double, val index: Int, val providerRank: Int)
}
