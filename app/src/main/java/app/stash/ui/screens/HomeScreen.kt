package app.stash.ui.screens

import app.stash.data.calm
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.stash.greet.GreetingAction
import app.stash.greet.GreetingViewModel
import app.stash.recs.RecsViewModel
import app.stash.ui.LocalActions
import app.stash.ui.LocalNav
import app.stash.ui.Routes
import app.stash.ui.components.Artwork
import app.stash.ui.components.CoverCard
import app.stash.ui.components.EmptyState
import app.stash.ui.components.SectionHeader
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val actions = LocalActions.current
    val nav = LocalNav.current
    val dao = actions.library.tracks
    val recent by remember { dao.observeRecent(20) .calm() }.collectAsState(initial = emptyList())
    val mostPlayed by remember { dao.observeMostPlayed(15) .calm() }.collectAsState(initial = emptyList())
    val downloaded by remember { dao.observeDownloaded() .calm() }.collectAsState(initial = emptyList())
    val liked by remember { dao.observeLiked() .calm() }.collectAsState(initial = emptyList())
    val allPlaylists by actions.playlists.collectAsState()
    val pinned by actions.pinned.collectAsState()
    val playlists = remember(allPlaylists) { allPlaylists.filter { it.origin != "mix" } }
    val allMixes = remember(allPlaylists) { allPlaylists.filter { it.origin == "mix" }.sortedBy { mixOrder(it.originId) } }
    val mixes = allMixes.filter { it.originId?.startsWith("genre-") != true && it.originId?.startsWith("artist-") != true }
    val genreMixes = allMixes.filter { it.originId?.startsWith("genre-") == true }
    val artistMixes = allMixes.filter { it.originId?.startsWith("artist-") == true }
    val recs: RecsViewModel = hiltViewModel()
    val because by recs.becauseYouLiked.collectAsState()
    val rebuilding by recs.refreshing.collectAsState()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val greetingVm: GreetingViewModel = hiltViewModel()
    val greeting by greetingVm.greeting.collectAsState()
    // re-check on every visit (the place lookup itself is cached), e.g. right after turning it on
    LaunchedEffect(Unit) { greetingVm.refresh() }
    val scope = rememberCoroutineScope()

    fun runAction(a: GreetingAction) {
        scope.launch {
            when (a) {
                is GreetingAction.PlayArtist -> actions.shuffle(dao.byArtist(a.artist))
                GreetingAction.OpenDownloads -> nav.navigate(Routes.DOWNLOADS)
                GreetingAction.ShuffleLiked -> actions.shuffle(dao.liked())
                GreetingAction.ShuffleDownloads -> actions.shuffle(dao.downloaded())
            }
        }
    }

    // spotify's home glow: the top of the screen takes the color of what's playing (or played last)
    val nowArt = actions.player.state.collectAsState().value.artworkUri
    val (glow, _) = app.stash.ui.components.rememberArtColors(nowArt ?: recent.firstOrNull()?.artworkUrl)
    val glowColor by androidx.compose.animation.animateColorAsState(
        glow?.copy(alpha = 0.45f) ?: Color.Transparent, androidx.compose.animation.core.tween(600), label = "glow",
    )
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
    Box(Modifier.fillMaxWidth().height(340.dp).background(Brush.verticalGradient(listOf(glowColor, Color.Transparent))))
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            LargeTopAppBar(
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = Color.Transparent),
                title = { Text(greeting.title, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.DOWNLOADS) }) { Icon(Icons.Rounded.DownloadForOffline, "downloads") }
                    IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Icon(Icons.Rounded.Settings, "settings") }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        val empty = recent.isEmpty() && playlists.isEmpty() && downloaded.isEmpty() && liked.isEmpty()
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp)) {
            greeting.message?.let { msg ->
                item(key = "greeting") { GreetingCard(msg, greeting.action?.takeUnless { empty }, ::runAction) }
            }
            if (empty) {
                item {
                    EmptyState(
                        Icons.Rounded.Search,
                        "Your Stash is empty",
                        "Search for something to play, or bring your playlists over from Spotify or Apple Music.",
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { nav.navigate(Routes.SEARCH) }) { Text("Search") }
                            OutlinedButton(onClick = { nav.navigate(Routes.import()) }) { Text("Import playlists") }
                        }
                    }
                }
                return@LazyColumn
            }

            // spotify-style quick picks: a two column grid of compact tiles
            item {
                val tiles = buildList {
                    // pinned playlists always come first, then the usual picks
                    pinned.forEach { p -> add(QuickTile(p.name, p.art, null, pinned = true) { nav.navigate(Routes.playlist(p.id)) }) }
                    if (liked.isNotEmpty()) add(QuickTile("Liked songs", null, Icons.Rounded.Favorite) { nav.navigate(Routes.LIKED) })
                    if (downloaded.isNotEmpty()) add(QuickTile("Downloads", null, Icons.Rounded.Download) { nav.navigate(Routes.DOWNLOADS) })
                    playlists.filter { p -> pinned.none { it.id == p.id } }.take(maxOf(0, 8 - size)).forEach { p ->
                        add(QuickTile(p.name, p.art, null) { nav.navigate(Routes.playlist(p.id)) })
                    }
                }
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tiles.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { QuickPick(it, Modifier.weight(1f)) }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            if (mixes.isNotEmpty() || rebuilding) {
                item {
                    SectionHeader("Made for you") {
                        if (rebuilding) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                        else IconButton(onClick = { recs.rebuildMixes() }) { Icon(Icons.Rounded.Refresh, "Make new mixes") }
                    }
                }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(mixes, key = { it.id }) { m ->
                            MixCard(m.name, m.description, m.art) { nav.navigate(Routes.playlist(m.id)) }
                        }
                    }
                }
            }

            if (genreMixes.isNotEmpty()) {
                item { SectionHeader("Your genres") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(genreMixes, key = { it.id }) { m ->
                            MixCard(m.name, m.description?.substringAfter("· "), m.art) { nav.navigate(Routes.playlist(m.id)) }
                        }
                    }
                }
            }

            if (artistMixes.isNotEmpty()) {
                item { SectionHeader("Artist mixes") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(artistMixes, key = { it.id }) { m ->
                            MixCard(m.name, m.description, m.art) { nav.navigate(Routes.playlist(m.id)) }
                        }
                    }
                }
            }

            if (recent.isNotEmpty()) {
                item { SectionHeader("Recently played") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(recent, key = { _, t -> t.id }) { i, t ->
                            CoverCard(t.title, t.artist, t.artworkUrl, onClick = { actions.play(recent, i) })
                        }
                    }
                }
            }

            because?.let { (seed, list) ->
                item { SectionHeader("Because you liked ${seed.title}") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(list, key = { _, t -> "b:${t.id}" }) { i, t ->
                            CoverCard(t.title, t.artist, t.artworkUrl, onClick = { actions.play(list, i) }, size = 120.dp)
                        }
                    }
                }
            }

            if (playlists.isNotEmpty()) {
                item { SectionHeader("Your playlists") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(playlists, key = { it.id }) { p ->
                            CoverCard(p.name, "${p.trackCount} songs", p.art, onClick = { nav.navigate(Routes.playlist(p.id)) })
                        }
                    }
                }
            }

            if (mostPlayed.size >= 3) {
                item { SectionHeader("On repeat") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(mostPlayed, key = { _, t -> t.id }) { i, t ->
                            CoverCard(t.title, "${t.playCount} plays", t.artworkUrl, onClick = { actions.play(mostPlayed, i) }, size = 120.dp)
                        }
                    }
                }
            }

            if (downloaded.isNotEmpty()) {
                item {
                    SectionHeader("Ready offline") {
                        IconButton(onClick = { actions.shuffle(downloaded) }) { Icon(Icons.Rounded.Shuffle, "Shuffle downloads") }
                    }
                }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(downloaded.take(30), key = { _, t -> t.id }) { i, t ->
                            CoverCard(t.title, t.artist, t.artworkUrl, onClick = { actions.play(downloaded, i) }, size = 120.dp)
                        }
                    }
                }
            }
        }
    }
    }
}

private data class QuickTile(
    val title: String,
    val art: Any?,
    val icon: ImageVector?,
    val pinned: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun QuickPick(tile: QuickTile, modifier: Modifier) {
    Surface(
        onClick = tile.onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        modifier = modifier.height(56.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (tile.icon != null) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(56.dp)) {
                    Icon(tile.icon, null, Modifier.padding(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            } else {
                Artwork(tile.art, Modifier.size(56.dp), RectangleShape)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                tile.title, style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            if (tile.pinned) Icon(
                Icons.Rounded.PushPin, "Pinned", Modifier.padding(end = 8.dp).size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun GreetingCard(message: String, action: GreetingAction?, onAction: (GreetingAction) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    ) {
        Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
            if (action != null) TextButton(onClick = { onAction(action) }) { Text(action.label, maxLines = 1) }
        }
    }
}

/** daily mix 1..4, then discover, then rewind; genre and artist mixes keep their order */
private fun mixOrder(originId: String?) = when {
    originId == null -> 99
    originId.startsWith("daily-") -> originId.removePrefix("daily-").toIntOrNull() ?: 0
    originId == "discover" -> 10
    originId == "rewind" -> 20
    else -> 30
}

/** spotify-style mix tile: the art with the name sitting on a dark fade at the bottom */
@Composable
private fun MixCard(name: String, about: String?, art: Any?, onClick: () -> Unit) {
    Column(Modifier.width(156.dp)) {
        Surface(onClick = onClick, shape = MaterialTheme.shapes.large, modifier = Modifier.size(156.dp)) {
            // the cover itself carries the name and the stash mark (MixArt), like spotify's mixes
            Artwork(art, Modifier.fillMaxSize(), RectangleShape)
        }
        if (about != null) Text(
            about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}
