package app.stash.ui.screens

import app.stash.data.calm
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.ui.Alignment
import app.stash.data.db.PlaylistWithCount
import app.stash.ui.components.LikedArt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stash.ui.LocalActions
import app.stash.ui.LocalNav
import app.stash.ui.Routes
import app.stash.ui.components.Artwork
import app.stash.ui.components.EmptyState
import app.stash.ui.components.NewPlaylistDialog
import app.stash.ui.components.RescanStatus
import app.stash.ui.components.TrackRow
import app.stash.ui.components.originName
import kotlinx.coroutines.launch

private enum class LibFilter(val label: String) { PLAYLISTS("Playlists"), SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists"), DOWNLOADED("Downloaded") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    val actions = LocalActions.current
    val nav = LocalNav.current
    val dao = actions.library.tracks
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf(LibFilter.PLAYLISTS) }
    var creating by remember { mutableStateOf(false) }
    var alphabetical by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(false) }
    val playlists by actions.libraryPlaylists.collectAsState()
    val songs by remember { dao.observeLibrary() .calm() }.collectAsState(initial = emptyList())
    val liked by remember { dao.observeLiked() .calm() }.collectAsState(initial = emptyList())
    val albums by remember { dao.observeAlbums() .calm() }.collectAsState(initial = emptyList())
    val artists by remember { dao.observeArtists() .calm() }.collectAsState(initial = emptyList())
    val downloaded by remember { dao.observeDownloaded() .calm() }.collectAsState(initial = emptyList())
    val currentId by actions.player.currentId.collectAsState()
    val missingCount by actions.missingCount.collectAsState()
    val pinned by actions.pinned.collectAsState()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Your library") },
                actions = {
                    IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, "New playlist") }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp)) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LibFilter.entries) { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                    }
                }
                // spotify's "Recents" sort and list / grid switch
                if (filter == LibFilter.PLAYLISTS) Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { alphabetical = !alphabetical }) {
                        Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (alphabetical) "Alphabetical" else "Recents")
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { grid = !grid }) {
                        Icon(if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView, if (grid) "List" else "Grid")
                    }
                } else Spacer(Modifier.size(8.dp))
            }

            when (filter) {
                LibFilter.PLAYLISTS -> {
                    val pinnedIds = pinned.map { it.id }.toSet()
                    // pinned ones stay on top, like spotify's library
                    val pinnedHere = pinned.filter { pp -> playlists.any { it.id == pp.id } }
                    val sorted = pinnedHere + (if (alphabetical) playlists.sortedBy { it.name.lowercase() } else playlists)
                        .filter { it.id !in pinnedIds }
                    if (!grid) item {
                        LibraryRow(
                            title = "Liked songs", subtitle = "Pinned · Playlist · ${liked.size} songs", art = null,
                            onClick = { nav.navigate(Routes.LIKED) },
                        ) { LikedArt(Modifier.size(56.dp)) }
                    }
                    // only shows up while something couldn't be found
                    if (missingCount > 0) item {
                        ListItem(
                            headlineContent = { Text("$missingCount songs couldn't be found") },
                            supportingContent = { RescanStatus(Modifier.fillMaxWidth().padding(top = 4.dp)) },
                            leadingContent = { PinnedIcon(Icons.Rounded.Refresh) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { nav.navigate(Routes.RESCAN) }.padding(horizontal = 4.dp),
                        )
                    }
                    if (grid) {
                        val cells = listOf<PlaylistWithCount?>(null) + sorted
                        items(cells.chunked(3), key = { row -> row.joinToString { it?.id?.toString() ?: "liked" } }) { row ->
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { p ->
                                    Column(Modifier.weight(1f).clickable { nav.navigate(if (p == null) Routes.LIKED else Routes.playlist(p.id)) }) {
                                        val art = Modifier.fillMaxWidth().aspectRatio(1f)
                                        if (p == null) LikedArt(art) else Artwork(p.art, art, MaterialTheme.shapes.small)
                                        Spacer(Modifier.height(6.dp))
                                        Text(p?.name ?: "Liked songs", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            if (p == null) "${liked.size} songs" else "${p.trackCount} songs",
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                        )
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    } else items(sorted, key = { it.id }) { p ->
                        val sub = buildList {
                            if (p.id in pinnedIds) add("Pinned")
                            add("Playlist")
                            add("${p.trackCount} songs")
                            if (p.downloadedCount > 0) add(if (p.downloadedCount == p.trackCount) "Downloaded" else "${p.downloadedCount} offline")
                            if (p.origin == "mix") add("Made for you") else if (p.origin != "local") add("From ${originName(p.origin)}")
                        }.joinToString(" · ")
                        LibraryRow(
                            p.name, sub, p.art, onClick = { nav.navigate(Routes.playlist(p.id)) },
                            onLongClick = { actions.setPinned(p.id, p.id !in pinnedIds) },
                        )
                    }
                    if (playlists.isEmpty()) item {
                        EmptyState(Icons.AutoMirrored.Rounded.QueueMusic, "No playlists yet", "Make one, or bring yours over from Spotify or Apple Music.") {
                            TextButton(onClick = { nav.navigate(Routes.import()) }) { Text("Import playlists") }
                        }
                    }
                }

                LibFilter.SONGS -> {
                    if (songs.isEmpty()) item {
                        EmptyState(Icons.Rounded.LibraryMusic, "No saved songs", "Long-press any song and pick \"Save to library\".")
                    } else item {
                        Row(Modifier.padding(horizontal = 12.dp)) {
                            TextButton(onClick = { actions.shuffle(songs) }) {
                                Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Shuffle all")
                            }
                        }
                    }
                    itemsIndexed(songs, key = { _, t -> t.id }) { i, t ->
                        TrackRow(t, onClick = { actions.play(songs, i) }, isCurrent = currentId == t.id)
                    }
                }

                LibFilter.ALBUMS -> {
                    if (albums.isEmpty()) item { EmptyState(Icons.Rounded.Album, "No albums", "Songs with album info show up here, grouped.") }
                    items(albums, key = { "${it.album}|${it.artist}" }) { a ->
                        LibraryRow(a.album, "Album · ${a.artist}", a.artworkUrl, onClick = { nav.navigate(Routes.album(a.album, a.artist)) })
                    }
                }

                LibFilter.ARTISTS -> {
                    if (artists.isEmpty()) item { EmptyState(Icons.Rounded.Person, "No artists", "Save some songs and their artists land here.") }
                    items(artists, key = { it.artist }) { a ->
                        LibraryRow(a.artist, "${a.trackCount} songs", a.artworkUrl, circle = true, onClick = { nav.navigate(Routes.artist(a.artist)) })
                    }
                }

                LibFilter.DOWNLOADED -> {
                    if (downloaded.isEmpty()) item {
                        EmptyState(Icons.Rounded.DownloadDone, "Nothing offline yet", "Hit download on a song, album or playlist.")
                    } else item {
                        Row(Modifier.padding(horizontal = 12.dp)) {
                            TextButton(onClick = { actions.shuffle(downloaded) }) {
                                Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Shuffle offline")
                            }
                            TextButton(onClick = { nav.navigate(Routes.DOWNLOADS) }) { Text("Manage downloads") }
                        }
                    }
                    itemsIndexed(downloaded, key = { _, t -> t.id }) { i, t ->
                        TrackRow(t, onClick = { actions.play(downloaded, i) }, isCurrent = currentId == t.id)
                    }
                }
            }
        }
    }

    if (creating) NewPlaylistDialog(
        onDismiss = { creating = false },
        onCreate = { name -> scope.launch { nav.navigate(Routes.playlist(actions.library.createPlaylist(name))) } },
    )
}

@Composable
private fun PinnedIcon(icon: ImageVector) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.size(56.dp)) {
        Icon(icon, null, Modifier.padding(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryRow(
    title: String,
    subtitle: String,
    art: Any?,
    onClick: () -> Unit,
    circle: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = leading ?: {
            Artwork(art, Modifier.size(56.dp), if (circle) CircleShape else MaterialTheme.shapes.small,
                if (circle) Icons.Rounded.Person else Icons.AutoMirrored.Rounded.QueueMusic)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 4.dp),
    )
}
