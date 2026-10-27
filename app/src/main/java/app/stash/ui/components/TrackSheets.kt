package app.stash.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stash.data.db.DownloadState
import app.stash.data.db.Track
import app.stash.ui.LocalActions
import app.stash.ui.LocalNav
import app.stash.ui.Routes
import kotlinx.coroutines.launch

/** an extra row a screen can add to the track sheet, e.g. "Remove from this playlist" */
data class SheetAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

val LocalExtraTrackActions = compositionLocalOf<(Track) -> List<SheetAction>> { { emptyList() } }

/** the spotify-style long-press sheet */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackOptionsSheet(track: Track, onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val nav = LocalNav.current
    val extra = LocalExtraTrackActions.current(track)
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // the row passed in may be a stale search result, watch the db copy for liked/download state
    val live by remember(track.id) { actions.library.tracks.observe(track.id) }.collectAsState(initial = null)
    val t = live ?: track
    var pickPlaylist by remember { mutableStateOf(false) }

    fun close(then: () -> Unit) {
        scope.launch { state.hide() }.invokeOnCompletion { onDismiss(); then() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(t.artworkUrl, Modifier.size(56.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(t.artist, t.album).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "From ${t.resolvedProvider?.let { "${t.provider} → $it" } ?: t.provider}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // the heart's playlist (liked songs unless changed in settings)
            val hearted by remember(t.id) { actions.hearted(t.id) }.collectAsState(initial = t.liked)
            val heartName = actions.heartTarget.collectAsState().value?.name ?: "liked songs"
            SheetRow(if (hearted) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (hearted) "Remove from $heartName" else "Add to $heartName") {
                actions.toggleHeart(t, hearted); close {}
            }
            SheetRow(Icons.Rounded.Radio, "Start radio") { actions.startRadio(t); close {} }
            SheetRow(Icons.Rounded.PlaylistPlay, "Play next") { actions.playNext(t); close {} }
            SheetRow(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue") { actions.addToQueue(listOf(t)); close {} }
            SheetRow(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist") { pickPlaylist = true }
            if (t.inLibrary) {
                SheetRow(Icons.Rounded.LibraryAddCheck, "Remove from library") { actions.library.removeFromLibrary(t); close {} }
            } else {
                SheetRow(Icons.Rounded.LibraryAdd, "Save to library") { actions.library.save(t); actions.toast("Saved"); close {} }
            }
            when (t.downloadState) {
                DownloadState.DONE -> SheetRow(Icons.Rounded.Delete, "Remove download") { actions.removeDownload(t); close {} }
                DownloadState.QUEUED, DownloadState.DOWNLOADING ->
                    SheetRow(Icons.Rounded.RemoveCircleOutline, "Cancel download") { actions.downloads.cancel(t); close {} }
                else -> SheetRow(Icons.Rounded.Download, "Download") { actions.download(listOf(t)); close {} }
            }
            // the matcher picked a live version / cover / wrong song: pick the right one by hand
            if (!t.isDownloaded) SheetRow(Icons.Rounded.FindReplace, "Wrong song? Pick the right one") {
                close { nav.navigate(app.stash.ui.Routes.rematch(t.id, "${t.artist} ${t.title}")) }
            }
            if (t.artist.isNotBlank()) SheetRow(Icons.Rounded.Person, "Go to artist") {
                close { nav.navigate(Routes.artist(t.artist)) }
            }
            if (!t.album.isNullOrBlank()) SheetRow(Icons.Rounded.Album, "Go to album") {
                close { nav.navigate(Routes.album(t.album, t.artist)) }
            }
            extra.forEach { a -> SheetRow(a.icon, a.label) { a.onClick(); close {} } }
        }
    }

    if (pickPlaylist) AddToPlaylistDialog(listOf(t), onDismiss = { pickPlaylist = false; close {} })
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

@Composable
fun AddToPlaylistDialog(tracks: List<Track>, onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val playlists by actions.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }

    if (creating) {
        NewPlaylistDialog(
            onDismiss = { creating = false },
            onCreate = { name -> actions.newPlaylistWith(name, tracks); onDismiss() },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            LazyColumn(Modifier.height(360.dp)) {
                item {
                    ListItem(
                        headlineContent = { Text("New playlist") },
                        leadingContent = { Icon(Icons.Rounded.Add, null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { creating = true },
                    )
                }
                items(playlists, key = { it.id }) { p ->
                    ListItem(
                        headlineContent = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${p.trackCount} songs") },
                        leadingContent = { Artwork(p.art, Modifier.size(44.dp)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            actions.addToPlaylist(p.id, p.name, tracks); onDismiss()
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun NewPlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit, initial: String = "", title: String = "New playlist") {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("Name") }, shape = MaterialTheme.shapes.medium,
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.ifBlank { "New playlist" }); onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
