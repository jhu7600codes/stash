package app.stash.ui.tv

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import app.stash.cloud.Pairing
import app.stash.data.calm
import app.stash.data.db.LIKED_PLAYLIST_ID
import app.stash.data.db.MixCover
import app.stash.data.db.Track
import app.stash.ui.AppViewModel
import app.stash.ui.LocalActions
import app.stash.ui.player.LyricsView
import app.stash.ui.components.Artwork
import app.stash.ui.components.LikedArt
import app.stash.ui.components.formatDuration
import app.stash.ui.screens.SearchViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** where the tv app is; a back press goes one step up the stack */
private sealed interface TvPage {
    data object Home : TvPage
    data object Search : TvPage
    data object Library : TvPage
    data object Account : TvPage
    data object Player : TvPage
    data class Playlist(val id: Long) : TvPage
}

/**
 * stash on android tv (and anything with a remote): a rail on the left, big cards, everything
 * reachable with the d-pad, and the focused thing always lit up. logging in is a code you type
 * on your phone (Create → Pair a device), no keyboard needed.
 */
/** what the tv app needs from the activity (account / backup live there) */
class TvHooks(
    /** pull the account's playlists in; runs outside any screen so leaving a screen can't cancel it */
    val sync: () -> Unit,
    val logOut: suspend () -> Unit,
    val sessions: suspend () -> Int?,
    val logOutOthers: suspend () -> Unit,
)

@Composable
fun TvApp(app: AppViewModel, hooks: TvHooks) {
    val settings by app.settings.collectAsState()
    var skipped by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(Unit) { app.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }
    val s = settings ?: return
    // logged in but never got the account's playlists (or that got interrupted): get them now
    LaunchedEffect(s.accountEmail, s.cloudReady) { if (s.accountEmail.isNotEmpty() && !s.cloudReady) hooks.sync() }
    // a surface, so text and icons get the theme's light color (a bare box left them black)
    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        androidx.compose.runtime.CompositionLocalProvider(LocalActions provides app) {
            if (s.accountEmail.isEmpty() && !skipped) PairScreen(app.pairing, onPaired = hooks.sync, onSkip = { skipped = true })
            else TvShell(app, s.accountEmail, hooks, onLogOut = { hooks.logOut(); skipped = false })
        }
    }
}

@Composable
private fun TvShell(app: AppViewModel, email: String, hooks: TvHooks, onLogOut: suspend () -> Unit) {
    val stack = remember { mutableStateListOf<TvPage>(TvPage.Home) }
    val page = stack.last()
    val currentId by app.player.currentId.collectAsState()
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }
    fun go(p: TvPage, top: Boolean = false) {
        if (top) { stack.clear(); stack += TvPage.Home; if (p != TvPage.Home) stack += p }
        else if (stack.last() != p) stack += p
    }
    fun playAndOpen(list: List<Track>, i: Int, shuffle: Boolean = false) {
        if (shuffle) app.shuffle(list) else app.play(list, i)
        go(TvPage.Player)
    }

    Row(Modifier.fillMaxSize()) {
        // the rail: always on the left, d-pad left gets you there from anywhere
        Column(
            Modifier.width(220.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(vertical = 32.dp, horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("stash", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 12.dp, bottom = 24.dp))
            RailItem(Icons.Rounded.Search, "Search", page == TvPage.Search) { go(TvPage.Search, top = true) }
            RailItem(Icons.Rounded.Home, "Home", page == TvPage.Home) { go(TvPage.Home, top = true) }
            RailItem(Icons.Rounded.LibraryMusic, "Library", page == TvPage.Library) { go(TvPage.Library, top = true) }
            if (currentId != null) RailItem(Icons.Rounded.GraphicEq, "Now playing", page == TvPage.Player) { go(TvPage.Player) }
            Spacer(Modifier.weight(1f))
            RailItem(Icons.Rounded.Settings, "Settings", page == TvPage.Account) { go(TvPage.Account, top = true) }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            AnimatedContent(page, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tvPage") { p ->
                when (p) {
                    TvPage.Home -> HomePage(app, open = { go(TvPage.Playlist(it)) }, play = ::playAndOpen)
                    TvPage.Search -> SearchPage(play = ::playAndOpen)
                    TvPage.Library -> LibraryPage(app, open = { go(TvPage.Playlist(it)) })
                    TvPage.Account -> SettingsPage(app, email, hooks, onLogOut)
                    TvPage.Player -> PlayerPage(app)
                    is TvPage.Playlist -> PlaylistPage(app, p.id, play = ::playAndOpen)
                }
            }
        }
    }
}

/** focus look for every tv control: lifts a little and gets a white outline */
@Composable
private fun Modifier.tvFocus(shape: Shape = RoundedCornerShape(14.dp), lift: Float = 1.06f): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) lift else 1f, label = "focus")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .border(3.dp, if (focused) Color.White else Color.Transparent, shape)
        .clip(shape)
}

@Composable
private fun RailItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().tvFocus(RoundedCornerShape(50), lift = 1.03f)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun TvCard(
    title: String, subtitle: String?, art: Any?, onClick: () -> Unit,
    modifier: Modifier = Modifier, size: Dp = 180.dp, artSlot: (@Composable () -> Unit)? = null,
) {
    Column(modifier.width(size)) {
        Box(Modifier.size(size).tvFocus().clickable(onClick = onClick)) {
            if (artSlot != null) artSlot() else Artwork(art, Modifier.fillMaxSize(), RoundedCornerShape(14.dp))
        }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        if (subtitle != null) Text(
            subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TvButton(icon: ImageVector?, label: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier.tvFocus(RoundedCornerShape(50))
            .background(if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        if (icon != null) { Icon(icon, null, tint = fg); Spacer(Modifier.width(10.dp)) }
        Text(label, color = fg, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun RowTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 48.dp, top = 28.dp, bottom = 14.dp))
}

@Composable
private fun HomePage(app: AppViewModel, open: (Long) -> Unit, play: (List<Track>, Int, Boolean) -> Unit) {
    val dao = app.library.tracks
    val recent by remember { dao.observeRecent(20).calm() }.collectAsState(initial = emptyList())
    val most by remember { dao.observeMostPlayed(20).calm() }.collectAsState(initial = emptyList())
    val liked by remember { dao.observeLiked().calm() }.collectAsState(initial = emptyList())
    val all by app.playlists.collectAsState()
    val own = all.filter { it.origin != "mix" }
    val mixes = all.filter { it.origin == "mix" }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }

    LazyColumn(contentPadding = PaddingValues(bottom = 48.dp)) {
        item { Text("Home", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(start = 48.dp, top = 40.dp)) }
        item { RowTitle("Your playlists") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                item {
                    TvCard("Liked songs", "${liked.size} songs", null, onClick = { open(LIKED_PLAYLIST_ID) },
                        modifier = Modifier.focusRequester(first)) { LikedArt(Modifier.fillMaxSize()) }
                }
                itemsIndexed(own, key = { _, p -> p.id }) { _, p -> TvCard(p.name, "${p.trackCount} songs", p.art, onClick = { open(p.id) }) }
            }
        }
        if (mixes.isNotEmpty()) {
            item { RowTitle("Made for you") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    itemsIndexed(mixes, key = { _, p -> p.id }) { _, p -> TvCard(p.name, p.description, p.art, onClick = { open(p.id) }) }
                }
            }
        }
        if (recent.isNotEmpty()) {
            item { RowTitle("Recently played") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    itemsIndexed(recent, key = { _, t -> t.id }) { i, t -> TvCard(t.title, t.artist, t.artworkUrl, onClick = { play(recent, i, false) }, size = 160.dp) }
                }
            }
        }
        if (most.size >= 3) {
            item { RowTitle("On repeat") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    itemsIndexed(most, key = { _, t -> t.id }) { i, t -> TvCard(t.title, "${t.playCount} plays", t.artworkUrl, onClick = { play(most, i, false) }, size = 160.dp) }
                }
            }
        }
    }
}

@Composable
private fun LibraryPage(app: AppViewModel, open: (Long) -> Unit) {
    val playlists by app.libraryPlaylists.collectAsState()
    val liked by remember { app.library.tracks.observeLiked().calm() }.collectAsState(initial = emptyList())
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    Column {
        Text("Your library", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(start = 48.dp, top = 40.dp, bottom = 24.dp))
        LazyVerticalGrid(
            GridCells.Adaptive(190.dp), contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            item {
                TvCard("Liked songs", "${liked.size} songs", null, onClick = { open(LIKED_PLAYLIST_ID) }, modifier = Modifier.focusRequester(first)) {
                    LikedArt(Modifier.fillMaxSize())
                }
            }
            itemsIndexed(playlists, key = { _, p -> p.id }) { _, p -> TvCard(p.name, "${p.trackCount} songs", p.art, onClick = { open(p.id) }) }
        }
    }
}

@Composable
private fun PlaylistPage(app: AppViewModel, id: Long, play: (List<Track>, Int, Boolean) -> Unit) {
    val liked = id == LIKED_PLAYLIST_ID
    val playlist by remember(id) { app.library.playlists.observe(id).calm() }.collectAsState(initial = null)
    val entries by remember(id) { app.library.playlists.observeEntries(id).calm() }.collectAsState(initial = emptyList())
    val likedTracks by remember { app.library.tracks.observeLiked().calm() }.collectAsState(initial = emptyList())
    val tracks = if (liked) likedTracks else entries.map { it.track }
    val currentId by app.player.currentId.collectAsState()
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(id) { delay(150); runCatching { playFocus.requestFocus() } }

    LazyColumn(contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 48.dp)) {
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                if (liked) LikedArt(Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)))
                else Artwork(
                    playlist?.let { MixCover.of(it.origin, it.originId, it.name, it.coverUrl) ?: it.coverUrl }
                        ?: tracks.firstOrNull()?.artworkUrl,
                    Modifier.size(220.dp), RoundedCornerShape(16.dp),
                )
                Spacer(Modifier.width(32.dp))
                Column {
                    Text("Playlist", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (liked) "Liked songs" else playlist?.name.orEmpty(), style = MaterialTheme.typography.displaySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text("${tracks.size} songs", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TvButton(Icons.Rounded.PlayArrow, "Play", Modifier.focusRequester(playFocus), primary = true) { if (tracks.isNotEmpty()) play(tracks, 0, false) }
                        TvButton(Icons.Rounded.Shuffle, "Shuffle") { if (tracks.isNotEmpty()) play(tracks, 0, true) }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
        itemsIndexed(tracks, key = { i, t -> "$i:${t.id}" }) { i, t ->
            TrackLine(t, i, current = t.id == currentId) { play(tracks, i, false) }
        }
    }
}

@Composable
private fun TrackLine(t: Track, index: Int?, current: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).tvFocus(RoundedCornerShape(12.dp), lift = 1.015f)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) Text(
            "${index + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(44.dp),
        )
        Artwork(t.artworkUrl, Modifier.size(52.dp), RoundedCornerShape(8.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(t.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatDuration(t.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SearchPage(play: (List<Track>, Int, Boolean) -> Unit) {
    val vm: SearchViewModel = hiltViewModel()
    val ui by vm.ui.collectAsState()
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { field.requestFocus() } }
    val results = remember(ui) {
        (ui.local + ui.results.values.flatMap { r -> r.hits.map { it.track } }).distinctBy { it.id }
    }
    LazyColumn(contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 48.dp)) {
        item {
            Text("Search", style = MaterialTheme.typography.displaySmall)
            OutlinedTextField(
                value = ui.query, onValueChange = { vm.setQuery(it) },
                placeholder = { Text("Songs, artists or #tags") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth(0.7f).padding(vertical = 20.dp).focusRequester(field),
            )
        }
        itemsIndexed(results, key = { _, t -> t.id }) { i, t -> TrackLine(t, null, current = false) { play(results, i, false) } }
    }
}

@Composable
private fun PlayerPage(app: AppViewModel) {
    val state by app.player.state.collectAsState()
    var lyrics by remember { mutableStateOf(false) }
    if (lyrics) {
        // the phone's karaoke lyrics, full screen
        BackHandler { lyrics = false }
        Box(Modifier.fillMaxSize().background(Color(0xFF15121F)).padding(horizontal = 48.dp)) {
            LyricsView(state, Color(0xFF15121F), onClose = { lyrics = false })
        }
        return
    }
    val player = app.player
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { pos = player.positionMs; delay(500) } }
    val track by remember(state.currentId) { app.library.tracks.observe(state.currentId ?: "").calm() }.collectAsState(initial = null)
    val hearted by remember(state.currentId) { app.hearted(state.currentId ?: "") }.collectAsState(initial = false)
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { playFocus.requestFocus() } }

    Row(Modifier.fillMaxSize().padding(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(state.artworkUri, Modifier.fillMaxHeight(0.8f).aspectRatio(1f), RoundedCornerShape(24.dp))
        Spacer(Modifier.width(56.dp))
        Column(Modifier.weight(1f)) {
            Text(state.title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(state.artist, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (state.viaWeb) Text("Playing through the WebView", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
            state.error?.let { Text("Can't play this one: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(36.dp))
            LinearProgressIndicator(
                progress = { if (state.durationMs > 0) (pos.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(formatDuration(pos).ifEmpty { "0:00" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(formatDuration(state.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons.Rounded.Shuffle, "Shuffle", 56.dp, on = state.shuffle) { player.toggleShuffle() }
                RoundButton(Icons.Rounded.SkipPrevious, "Previous", 64.dp) { player.previous() }
                RoundButton(
                    if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play / pause", 84.dp,
                    primary = true, modifier = Modifier.focusRequester(playFocus),
                ) { player.togglePlay() }
                RoundButton(Icons.Rounded.SkipNext, "Next", 64.dp) { player.next() }
                RoundButton(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", 56.dp,
                    on = state.repeatMode != Player.REPEAT_MODE_OFF,
                ) { player.cycleRepeat() }
                RoundButton(Icons.Rounded.Lyrics, "Lyrics", 56.dp) { lyrics = true }
                track?.let { t ->
                    RoundButton(if (hearted) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Like", 56.dp, on = hearted) {
                        app.toggleHeart(t, hearted)
                    }
                }
            }
            if (state.queue.size > 1) {
                Text("Up next", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 36.dp, bottom = 8.dp))
                val next = state.queue.drop(state.queueIndex + 1).take(3)
                next.forEachIndexed { i, item ->
                    Text(
                        "${item.mediaMetadata.title} · ${item.mediaMetadata.artist}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.tvFocus(RoundedCornerShape(8.dp), lift = 1.02f)
                            .clickable { player.skipTo(state.queueIndex + 1 + i) }.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector, label: String, size: Dp, modifier: Modifier = Modifier,
    primary: Boolean = false, on: Boolean = false, onClick: () -> Unit,
) {
    Box(
        modifier.size(size).tvFocus(CircleShape, lift = 1.1f)
            .background(if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, label, Modifier.size(size * 0.45f),
            tint = when {
                primary -> MaterialTheme.colorScheme.onPrimary
                on -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/** settings on the tv: account (devices, log out), lyrics, playback */
@Composable
private fun SettingsPage(app: AppViewModel, email: String, hooks: TvHooks, onLogOut: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by app.settings.collectAsState()
    val s = settings ?: return
    var sessions by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(email) { if (email.isNotEmpty()) sessions = runCatching { hooks.sessions() }.getOrNull() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    val languages = listOf("" to "Phone's language", "en" to "English", "ru" to "Russian", "ja" to "Japanese", "uk" to "Ukrainian", "de" to "German", "es" to "Spanish")

    LazyColumn(contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(bottom = 16.dp)) }
        item { RowTitleSmall("Account") }
        item {
            Text(
                if (email.isEmpty()) "Not logged in. Log in to get your playlists from your phone." else "Logged in as $email",
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            sessions?.let { n ->
                Text(
                    if (n <= 1) "Logged in on this device only" else "Logged in on $n devices",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        item {
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvButton(null, if (email.isEmpty()) "Log in with a code" else "Log out", Modifier.focusRequester(first), primary = true) {
                    scope.launch { onLogOut() }
                }
                if (email.isNotEmpty()) TvButton(null, "Get my playlists again") { hooks.sync() }
                if ((sessions ?: 0) > 1) TvButton(null, "Log out other devices") {
                    scope.launch {
                        runCatching { hooks.logOutOthers() }
                            .onSuccess { sessions = 1; app.toast("Logged out every other device") }
                            .onFailure { app.toast(it.message ?: "Couldn't") }
                    }
                }
            }
        }
        item { RowTitleSmall("Lyrics") }
        item { TvToggle("Musixmatch", "Synced lyrics with translations, LRCLIB when it has none", s.lyricsMusixmatch) { v -> scope.launch { app.prefs.setLyricsMusixmatch(v) } } }
        item { TvToggle("Show translation", "A translation under every line", s.lyricsTranslate) { v -> scope.launch { app.prefs.setLyricsTranslate(v) } } }
        item {
            val i = languages.indexOfFirst { it.first == s.translateTo }.coerceAtLeast(0)
            TvToggle("Translate to", languages[i].second, null) {
                scope.launch { app.prefs.setTranslateTo(languages[(i + 1) % languages.size].first) }
            }
        }
        item { RowTitleSmall("Playback") }
        item { TvToggle("Autoplay", "Keep playing similar songs when the queue runs out", s.autoplay) { v -> scope.launch { app.prefs.setAutoplay(v) } } }
        item {
            val context = LocalContext.current
            val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
            Text("Stash $version", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
        }
    }
}

@Composable
private fun RowTitleSmall(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
}

/** a settings row: [on] null = not a switch, just tap to change */
@Composable
private fun TvToggle(title: String, about: String, on: Boolean?, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(0.75f).tvFocus(RoundedCornerShape(14.dp), lift = 1.02f)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { onChange(!(on ?: false)) }.padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(about, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (on != null) androidx.compose.material3.Switch(checked = on, onCheckedChange = null)
    }
}

/**
 * first launch on a tv: a code to type on your phone (Create → Pair a device). a new code
 * every 10 minutes; checks every 3 seconds whether the phone has sent it.
 */
@Composable
private fun PairScreen(pairing: Pairing, onPaired: () -> Unit, onSkip: () -> Unit) {
    var code by remember { mutableStateOf<Pairing.Code?>(null) }
    var status by remember { mutableStateOf("Getting a code…") }
    val skipFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(200); runCatching { skipFocus.requestFocus() } }
    LaunchedEffect(Unit) {
        val name = listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL).joinToString(" ").ifBlank { "TV" }
        while (true) {
            val c = runCatching { pairing.start(name) }.getOrElse {
                status = "Can't reach the server (${it.message}). Trying again…"
                delay(5000)
                null
            } ?: continue
            code = c
            status = "Waiting for your phone…"
            val until = System.currentTimeMillis() + c.expiresInSec * 1000
            var done = false
            while (System.currentTimeMillis() < until && !done) {
                delay(3000)
                when (val r = runCatching { pairing.poll(c) }.getOrNull()) {
                    is Pairing.Poll.Done -> {
                        status = "Logged in as ${r.email}. Getting your playlists…"
                        onPaired()
                        done = true
                    }
                    Pairing.Poll.Expired -> break
                    else -> Unit
                }
            }
            if (done) break
        }
    }

    Row(Modifier.fillMaxSize().padding(72.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Log in to stash", style = MaterialTheme.typography.displayMedium)
            Text(
                "Your playlists and liked songs, on the TV.",
                style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
            Column(Modifier.padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Step(1, "Open stash on your phone (logged in)")
                Step(2, "Tap Create, then Pair a device")
                Step(3, "Type the code on the right")
            }
            TvButton(null, "Skip for now", Modifier.padding(top = 48.dp).focusRequester(skipFocus), onClick = onSkip)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            // the six boxes share the column's width, so all six always fit
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                (code?.code ?: "······").forEach { ch ->
                    Box(
                        Modifier.weight(1f).aspectRatio(0.8f).clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) { Text("$ch", fontSize = 44.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Text("$n", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.titleLarge)
    }
}
