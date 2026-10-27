package app.stash.ui

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.stash.providers.RemoteArtist
import app.stash.ui.components.CreateSheet
import app.stash.ui.player.MiniPlayer
import app.stash.ui.player.NowPlayingSheet
import app.stash.ui.screens.AlbumScreen
import app.stash.ui.screens.ArtistPageScreen
import app.stash.ui.screens.DownloadsScreen
import app.stash.ui.screens.HomeScreen
import app.stash.ui.screens.ImportProgressScreen
import app.stash.ui.screens.RescanScreen
import app.stash.ui.screens.CoverGrabScreen
import app.stash.ui.screens.YouTubeLoginScreen
import app.stash.ui.screens.YtPlaylistScreen
import app.stash.ui.screens.ImportScreen
import app.stash.ui.screens.LibraryScreen
import app.stash.ui.screens.LikedScreen
import app.stash.ui.screens.PlaylistScreen
import app.stash.ui.screens.SearchScreen
import app.stash.ui.screens.SettingsScreen
import kotlinx.coroutines.flow.Flow

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val PLAYLIST = "playlist/{id}"
    const val ALBUM = "album?name={name}&artist={artist}"
    const val ARTIST = "artist?name={name}"
    const val LIKED = "liked"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val IMPORT = "import?link={link}"
    const val IMPORT_PROGRESS = "import/progress"
    const val RESCAN = "rescan"
    const val COVER_GRAB = "covers/exportify"
    const val YOUTUBE_LOGIN = "youtube/login"
    const val YT_PLAYLIST = "ytplaylist/{id}"
    const val FIX = "fix/{id}?q={q}"
    const val REMATCH = "rematch/{id}?q={q}"
    const val REMOTE_ARTIST = "rartist/{provider}/{id}?name={name}&art={art}"

    fun playlist(id: Long) = "playlist/$id"
    fun album(name: String, artist: String) = "album?name=${Uri.encode(name)}&artist=${Uri.encode(artist)}"
    fun artist(name: String) = "artist?name=${Uri.encode(name)}"
    fun import(link: String? = null) = if (link == null) "import" else "import?link=${Uri.encode(link)}"
    fun fix(unmatchedId: Long, query: String) = "fix/$unmatchedId?q=${Uri.encode(query)}"
    fun rematch(trackId: String, query: String) = "rematch/${Uri.encode(trackId)}?q=${Uri.encode(query)}"
    fun ytPlaylist(id: String) = "ytplaylist/${Uri.encode(id)}"
    fun remoteArtist(a: RemoteArtist) =
        "rartist/${a.provider}/${Uri.encode(a.id)}?name=${Uri.encode(a.name)}&art=${Uri.encode(a.avatarUrl ?: "")}"
}

val LocalNav = staticCompositionLocalOf<NavHostController> { error("No nav") }

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Outlined.Home, Icons.Rounded.Home),
    Tab(Routes.SEARCH, "Search", Icons.Outlined.Search, Icons.Rounded.Search),
    Tab(Routes.LIBRARY, "Your library", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
)

@Composable
fun StashRoot(app: AppViewModel, navRequests: Flow<String>, openPlayerRequests: Flow<Unit>) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    // only "is anything playing" here; the full player state is read inside the player views,
    // so position / buffering updates don't redraw the whole app
    val currentId by app.player.currentId.collectAsState()
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600

    LaunchedEffect(Unit) { app.messages.collect { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { navRequests.collect { nav.navigate(it) { launchSingleTop = true } } }
    LaunchedEffect(Unit) { openPlayerRequests.collect { showPlayer = true } }

    // the last resort: nothing could play the song, ask before handing it to the hidden webview
    val webOffer by app.player.web.offer.collectAsState()
    webOffer?.let { _ ->
        val title = app.player.state.collectAsState().value.title
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { app.player.web.decline() },
            title = { Text("Song can't be loaded, we can use a WebView to deliver it.") },
            text = {
                Text(
                    (if (title.isNotBlank()) "\"$title\" didn't play from any source. " else "") +
                        "Stash can play it from music.youtube.com in a hidden WebView, with your YouTube login if you have one. " +
                        "Skips to the next song by itself in 30 seconds."
                )
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { app.player.web.accept() }) { Text("Use WebView") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { app.player.web.decline() }) { Text("Skip") } },
        )
    }

    // opening the app: everything rises a little and fades in as the splash's bars fade out
    val appear = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(500, delayMillis = 80, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }

    CompositionLocalProvider(LocalActions provides app, LocalNav provides nav) {
        val backStack by nav.currentBackStackEntryAsState()
        Scaffold(
            modifier = Modifier.graphicsLayer {
                alpha = appear.value
                val sc = 0.96f + 0.04f * appear.value
                scaleX = sc; scaleY = sc
                translationY = (1f - appear.value) * 24.dp.toPx()
            },
            snackbarHost = { SnackbarHost(snackbar) },
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                Column {
                    AnimatedVisibility(
                        visible = currentId != null,
                        enter = slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(250)),
                        exit = slideOutVertically(tween(250, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(200)),
                    ) {
                        val player by app.player.state.collectAsState()
                        MiniPlayer(state = player, onOpen = { showPlayer = true })
                    }
                    if (!wide) NavigationBar {
                        tabs.forEach { tab ->
                            val selected = backStack?.destination?.hierarchy?.any { it.route == tab.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(if (selected) tab.selectedIcon else tab.icon, null) },
                                label = { Text(tab.label) },
                            )
                        }
                        // spotify's create button: not a page, a menu of ways to make something
                        NavigationBarItem(
                            selected = false,
                            onClick = { creating = true },
                            icon = { Icon(Icons.Rounded.Add, null) },
                            label = { Text("Create") },
                        )
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize()) {
            // tablets: the tabs go down the side instead of along the bottom
            if (wide) androidx.compose.material3.NavigationRail(
                modifier = Modifier.padding(top = padding.calculateTopPadding()).statusBarsPadding(),
            ) {
                tabs.forEach { tab ->
                    val selected = backStack?.destination?.hierarchy?.any { it.route == tab.route } == true
                    androidx.compose.material3.NavigationRailItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(if (selected) tab.selectedIcon else tab.icon, null) },
                        label = { Text(tab.label) },
                    )
                }
                androidx.compose.material3.NavigationRailItem(
                    selected = false, onClick = { creating = true },
                    icon = { Icon(Icons.Rounded.Add, null) }, label = { Text("Create") },
                )
            }
            NavHost(
                navController = nav,
                startDestination = Routes.HOME,
                modifier = Modifier.weight(1f).padding(padding).consumeWindowInsets(padding),
                // pages slide in a little from the side and fade, going back slides the other way
                enterTransition = { fadeIn(tween(260, easing = LinearOutSlowInEasing)) + slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 12 } },
                exitTransition = { fadeOut(tween(180)) + slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 24 } },
                popEnterTransition = { fadeIn(tween(260, easing = LinearOutSlowInEasing)) + slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 24 } },
                popExitTransition = { fadeOut(tween(180)) + slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 12 } },
            ) {
                composable(Routes.HOME) { HomeScreen() }
                composable(Routes.SEARCH) { SearchScreen() }
                composable(Routes.LIBRARY) { LibraryScreen() }
                composable(Routes.PLAYLIST, listOf(navArgument("id") { type = NavType.LongType })) {
                    PlaylistScreen(it.arguments!!.getLong("id"))
                }
                composable(Routes.ALBUM, listOf(navArgument("name") { defaultValue = "" }, navArgument("artist") { defaultValue = "" })) {
                    AlbumScreen(it.arguments!!.getString("name")!!, it.arguments!!.getString("artist")!!)
                }
                composable(Routes.ARTIST, listOf(navArgument("name") { defaultValue = "" })) { ArtistPageScreen() }
                composable(
                    Routes.REMOTE_ARTIST,
                    listOf(
                        navArgument("provider") {}, navArgument("id") {},
                        navArgument("name") { defaultValue = "" }, navArgument("art") { defaultValue = "" },
                    ),
                ) { ArtistPageScreen() }
                composable(Routes.LIKED) { LikedScreen() }
                composable(Routes.DOWNLOADS) { DownloadsScreen() }
                composable(Routes.SETTINGS) { SettingsScreen() }
                composable(Routes.IMPORT, listOf(navArgument("link") { nullable = true; defaultValue = null })) {
                    ImportScreen(it.arguments?.getString("link"))
                }
                composable(Routes.IMPORT_PROGRESS) { ImportProgressScreen() }
                composable(Routes.RESCAN) { RescanScreen() }
                composable(Routes.COVER_GRAB) { CoverGrabScreen() }
                composable(Routes.YOUTUBE_LOGIN) { YouTubeLoginScreen() }
                composable(Routes.YT_PLAYLIST, listOf(navArgument("id") {})) { YtPlaylistScreen() }
                composable(Routes.REMATCH, listOf(navArgument("id") {}, navArgument("q") { defaultValue = "" })) {
                    SearchScreen(fixTrackId = it.arguments!!.getString("id")!!, initialQuery = it.arguments!!.getString("q")!!)
                }
                composable(
                    Routes.FIX,
                    listOf(navArgument("id") { type = NavType.LongType }, navArgument("q") { defaultValue = "" }),
                ) {
                    SearchScreen(fixUnmatchedId = it.arguments!!.getLong("id"), initialQuery = it.arguments!!.getString("q")!!)
                }
            }
            }
        }

        if (creating) CreateSheet(onDismiss = { creating = false })
        // slides up over everything, bottom bar included
        AnimatedVisibility(
            visible = showPlayer && currentId != null,
            enter = slideInVertically(tween(380, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(320, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(260, delayMillis = 60)),
        ) {
            val player by app.player.state.collectAsState()
            NowPlayingSheet(state = player, onDismiss = { showPlayer = false })
        }
    }
}

