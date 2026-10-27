package app.stash.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stash.data.Library
import app.stash.data.db.Track
import app.stash.data.prefs.Settings
import app.stash.providers.MusicProvider
import app.stash.providers.ProviderRegistry
import app.stash.providers.RemoteArtist
import app.stash.providers.SearchHit
import app.stash.providers.SearchQuery
import app.stash.providers.SearchRanking
import app.stash.ui.LocalActions
import app.stash.ui.LocalNav
import app.stash.ui.Routes
import app.stash.ui.components.ArtistBubble
import app.stash.ui.components.Artwork
import app.stash.ui.components.TrackRow
import app.stash.ui.components.compactCount
import app.stash.ui.components.originName
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProviderResults(val loading: Boolean = true, val hits: List<SearchHit> = emptyList(), val error: String? = null)

data class SearchUi(
    val query: String = "",
    val providers: List<MusicProvider> = emptyList(),
    val underground: Boolean = false,
    val local: List<Track> = emptyList(),
    val results: Map<String, ProviderResults> = emptyMap(),
    val artists: List<RemoteArtist> = emptyList(),
    /** youtube music playlists for the query, spotify style */
    val playlists: List<app.stash.providers.RemotePlaylist> = emptyList(),
    val searched: String = "",
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val registry: ProviderRegistry,
    private val library: Library,
    private val settings: Settings,
    private val music: app.stash.providers.YouTubeMusicSearch,
) : ViewModel() {
    private val _ui = MutableStateFlow(SearchUi())
    val ui: StateFlow<SearchUi> = _ui
    val recent = settings.flow.map { it.recentSearches }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private var job: Job? = null
    private var debounce: Job? = null

    init {
        viewModelScope.launch { _ui.update { it.copy(providers = registry.active()) } }
    }

    fun setQuery(q: String, immediate: Boolean = false) {
        _ui.update { it.copy(query = q) }
        debounce?.cancel()
        if (q.isBlank()) {
            job?.cancel()
            _ui.update { it.copy(results = emptyMap(), local = emptyList(), artists = emptyList(), playlists = emptyList(), searched = "") }
            return
        }
        debounce = viewModelScope.launch {
            if (!immediate) delay(700)
            search()
        }
    }

    fun setUnderground(on: Boolean) {
        _ui.update { it.copy(underground = on) }
        if (_ui.value.searched.isNotBlank()) search(force = true)
    }

    /** a browse tile: tag search, optionally in underground mode */
    fun searchTag(tag: String, underground: Boolean) {
        _ui.update { it.copy(underground = underground) }
        setQuery("#$tag", immediate = true)
    }

    fun forgetRecent(q: String) = viewModelScope.launch { settings.removeRecentSearch(q) }

    fun search(force: Boolean = false) {
        val raw = _ui.value.query.trim()
        if (raw.isEmpty() || (!force && raw == _ui.value.searched)) return
        val query = SearchQuery.parse(raw)
        job?.cancel()
        job = viewModelScope.launch {
            settings.addRecentSearch(raw)
            val providers = registry.active().also { p -> _ui.update { it.copy(providers = p) } }
            _ui.update { s ->
                s.copy(searched = raw, artists = emptyList(), playlists = emptyList(), results = providers.associate { it.id to ProviderResults() })
            }
            launch {
                val local = if (query.tag != null) emptyList()
                else runCatching { library.tracks.searchLibrary(query.text) }.getOrDefault(emptyList())
                _ui.update { it.copy(local = local) }
            }
            launch {
                val found = runCatching { music.searchPlaylists(query.text) }.getOrDefault(emptyList())
                _ui.update { it.copy(playlists = found) }
            }
            // underground mode asks every source for more, since the big hits get filtered out
            val limit = if (_ui.value.underground) 30 else 20
            providers.forEach { p ->
                launch {
                    val r = runCatching { p.searchHits(query.text, limit, query.tag) }
                    _ui.update { s ->
                        s.copy(results = s.results + (p.id to ProviderResults(false, r.getOrDefault(emptyList()), r.exceptionOrNull()?.message)))
                    }
                }
                if (query.tag == null) launch {
                    val found = runCatching { p.searchArtists(query.text, 15) }.getOrDefault(emptyList())
                    if (found.isNotEmpty()) _ui.update { it.copy(artists = it.artists + found) }
                }
            }
        }
    }
}

private val browseTiles = listOf(
    "Pop", "Hip hop", "Rock", "Electronic", "Lo-fi", "Jazz", "Classical", "Indie",
    "Phonk", "R&B", "Metal", "Ambient", "Russian rap", "K-Pop", "Soundtracks", "Chill",
)

/** voicebanks: searched on vocadb by every version of the voice (utau, synthv…) */
private val vocalTiles = listOf(
    "Miku", "Teto UTAU", "Teto SynthV", "GUMI", "Rin", "Len", "Luka", "KAITO", "MEIKO", "IA", "Kafu", "Flower",
    "Una", "Zundamon",
)

/** scenes where most of the good stuff isn't on the big charts */
private val undergroundTiles = listOf(
    "Hyperpop", "Breakcore", "Memphis rap", "Drift phonk", "Bedroom pop", "Shoegaze", "Jersey club",
    "Plugg", "Digicore", "Midwest emo", "Witch house", "Dark ambient",
)

@Composable
fun SearchScreen(fixUnmatchedId: Long? = null, initialQuery: String = "", fixTrackId: String? = null) {
    val vm: SearchViewModel = hiltViewModel()
    val ui by vm.ui.collectAsState()
    val recent by vm.recent.collectAsState()
    val actions = LocalActions.current
    val nav = LocalNav.current
    val keyboard = LocalSoftwareKeyboardController.current
    val currentId by actions.player.currentId.collectAsState()
    val picking = fixUnmatchedId != null || fixTrackId != null

    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank() && ui.query.isBlank()) vm.setQuery(initialQuery, immediate = true)
    }

    fun onPick(list: List<Track>, index: Int) {
        if (fixTrackId != null) {
            actions.rematch(fixTrackId, list[index])
            nav.popBackStack()
        } else if (fixUnmatchedId != null) {
            actions.library.resolveUnmatched(fixUnmatchedId, list[index])
            actions.toast("Matched")
            nav.popBackStack()
        } else {
            actions.play(list, index)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (picking) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            }
            TextField(
                value = ui.query,
                onValueChange = { vm.setQuery(it) },
                placeholder = { Text(if (picking) "Find the right song" else "Songs, artists or #tags") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (ui.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Rounded.Close, "Clear") }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.search(force = true) }),
                modifier = Modifier.weight(1f),
            )
        }

        if (!picking) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = ui.underground,
                onClick = { vm.setUnderground(!ui.underground) },
                label = { Text("Underground") },
                leadingIcon = { Icon(Icons.Rounded.Explore, null, Modifier.size(18.dp)) },
            )
            if (ui.underground) Text(
                "Under 200K plays, least played first",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        if (ui.searched.isBlank()) {
            if (!picking) BrowseGrid(
                recent = recent,
                onRecent = { vm.setQuery(it, immediate = true) },
                onForget = { vm.forgetRecent(it) },
                onTag = { tag, underground -> vm.searchTag(tag, underground) },
            )
            return@Column
        }

        val query = remember(ui.searched) { SearchQuery.parse(ui.searched) }
        val loading = ui.results.values.any { it.loading }
        val hits = remember(ui.results, ui.local, ui.underground) {
            SearchRanking.merge(query, ui.local, ui.providers.map { it.id }, ui.results.mapValues { it.value.hits }, ui.underground)
        }
        val songs = remember(hits) { hits.map { it.track } }
        val artists = remember(ui.artists, ui.results, ui.underground) {
            // searched artists plus whoever uploaded the songs found (lots of small artists only show up that way)
            val uploaders = ui.results.values.flatMap { r -> r.hits.mapNotNull { it.uploader } }
            SearchRanking.mergeArtists(query.text, ui.artists + uploaders, ui.underground)
        }

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (artists.isNotEmpty() && !picking) {
                item { Text("Artists", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 12.dp)) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(artists, key = { "${it.provider}:${it.id}" }) { a ->
                            ArtistBubble(a) { nav.navigate(Routes.remoteArtist(a)) }
                        }
                    }
                }
            }
            if (ui.playlists.isNotEmpty() && !picking) {
                item { Text("Playlists", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 12.dp)) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(ui.playlists, key = { it.id }) { p ->
                            app.stash.ui.components.CoverCard(p.title, p.subtitle, p.coverUrl, onClick = { nav.navigate(Routes.ytPlaylist(p.id)) })
                        }
                    }
                }
            }
            if ((artists.isNotEmpty() || ui.playlists.isNotEmpty()) && !picking) {
                item { Text("Songs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 4.dp)) }
            }
            if (!loading && songs.isEmpty()) item {
                val error = ui.results.values.firstNotNullOfOrNull { it.error }
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (error != null) Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        when {
                            ui.underground -> "Nothing small enough found. Try turning Underground off"
                            error != null -> "Search didn't work: $error"
                            else -> "Nothing found"
                        },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(hits, key = { _, h -> h.track.id }) { i, h ->
                TrackRow(
                    h.track, onClick = { onPick(songs, i) }, isCurrent = currentId == h.track.id,
                    note = hitNote(h, query),
                    trailing = if (picking) null else ({
                        IconButton(onClick = { actions.download(listOf(h.track)) }) {
                            Icon(Icons.Rounded.Download, "Download", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }),
                )
            }
        }
    }
}

/** "12K plays · #phonk": how big it is, and a tag (the searched one if it has it) */
private fun hitNote(h: SearchHit, query: SearchQuery): String? {
    val plays = h.plays?.let { "${compactCount(it)} plays" }
    val tag = (h.tags.firstOrNull { it == query.tag } ?: h.tags.firstOrNull())?.let { "#${it.replace(' ', '-')}" }
    return listOfNotNull(plays, tag).joinToString(" · ").ifEmpty { null }
}

/** recent searches, then spotify's "browse all" with tonal tiles, voicebanks, then an underground section */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrowseGrid(
    recent: List<String>,
    onRecent: (String) -> Unit,
    onForget: (String) -> Unit,
    onTag: (tag: String, underground: Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val palette = listOf(
        scheme.primaryContainer to scheme.onPrimaryContainer,
        scheme.tertiaryContainer to scheme.onTertiaryContainer,
        scheme.secondaryContainer to scheme.onSecondaryContainer,
        scheme.surfaceContainerHighest to scheme.onSurface,
    )
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (recent.isNotEmpty()) {
            item { Text("Recent searches", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 6.dp)) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recent.forEach { q ->
                        InputChip(
                            selected = false, onClick = { onRecent(q) }, label = { Text(q) },
                            trailingIcon = {
                                Icon(Icons.Rounded.Close, "Forget", Modifier.size(16.dp).clickable { onForget(q) })
                            },
                        )
                    }
                }
            }
        }
        item { Text("Browse all", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 6.dp)) }
        tileRows(browseTiles, palette) { onTag(it.lowercase(), false) }
        item {
            Column(Modifier.padding(top = 10.dp)) {
                Text("Vocaloid & synth voices", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Songs by voicebank from VocaDB. Type #teto for every Teto, or #teto-synthv / #teto-utau for one",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        tileRows(vocalTiles, palette.drop(1) + palette.take(1)) { onTag(it.lowercase(), false) }
        item {
            Column(Modifier.padding(top = 10.dp)) {
                Text("Underground", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Small artists from SoundCloud, Audius and YouTube, least played first",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        tileRows(undergroundTiles, palette.reversed()) { onTag(it.lowercase(), true) }
    }
}

private fun LazyListScope.tileRows(
    tiles: List<String>,
    palette: List<Pair<Color, Color>>,
    onPick: (String) -> Unit,
) {
    items(tiles.chunked(2)) { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { genre ->
                val i = tiles.indexOf(genre)
                val (bg, fg) = palette[(i + i / 2) % palette.size]
                Surface(
                    onClick = { onPick(genre) },
                    color = bg,
                    contentColor = fg,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.weight(1f).height(92.dp),
                ) {
                    Box(Modifier.padding(14.dp)) {
                        Text(genre, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}
