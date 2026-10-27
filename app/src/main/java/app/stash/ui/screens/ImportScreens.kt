package app.stash.ui.screens

import app.stash.data.calm
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stash.R
import app.stash.data.db.LIKED_PLAYLIST_ID
import app.stash.importer.AppleMusic
import app.stash.importer.ExportifyCsv
import app.stash.importer.ImportQueue
import app.stash.importer.SpotifyLink
import app.stash.ui.LocalActions
import app.stash.ui.LocalNav
import app.stash.ui.Routes
import app.stash.ui.components.Artwork
import app.stash.ui.components.RescanStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class ImportViewModel @Inject constructor(
    val queue: ImportQueue,
    private val spotify: SpotifyLink,
    private val apple: AppleMusic,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val error = MutableStateFlow<String?>(null)
    val jobs = queue.jobs.calm().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val missing = queue.missing.calm().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** a spotify or apple music link (or any shared text containing one) */
    fun cleanLink(text: String) = spotify.findLink(text) ?: apple.findLink(text) ?: text.trim()

    /** a spotify link whose playlist is too big for the embed, waiting for the user to decide */
    val cutWarning = MutableStateFlow<String?>(null)
    val checking = MutableStateFlow(false)

    fun importLink(link: String, force: Boolean = false, onStarted: () -> Unit) {
        error.value = null
        cutWarning.value = null
        if ("spotify" !in link && "music.apple.com" !in link) {
            error.value = "Paste a Spotify or Apple Music playlist link"
            return
        }
        viewModelScope.launch {
            checking.value = true
            try {
                if ("spotify" in link && !force) {
                    val count = spotify.songCount(link)
                    if (count != null && count > 100) {
                        cutWarning.value = link
                        return@launch
                    }
                }
                val playlist = if ("spotify" in link) spotify.playlist(link) else apple.playlist(link)
                queue.enqueue(listOf(playlist))
                onStarted()
            } catch (e: Exception) {
                error.value = e.message ?: "Couldn't read that playlist"
            } finally {
                checking.value = false
            }
        }
    }

    /** reads the picked exportify csv / zip files, then queues every playlist in them */
    fun importFiles(uris: List<Uri>, onStarted: () -> Unit) {
        error.value = null
        viewModelScope.launch {
            checking.value = true
            val playlists = withContext(Dispatchers.IO) {
                uris.flatMap { uri ->
                    runCatching {
                        context.contentResolver.openInputStream(uri)!!.use { ExportifyCsv.read(displayName(uri), it) }
                    }.onFailure { error.value = "Couldn't read ${displayName(uri)}: ${it.message}" }.getOrDefault(emptyList())
                }
            }
            checking.value = false
            if (playlists.isEmpty()) {
                if (error.value == null) error.value = "No songs found, is that an Exportify CSV?"
                return@launch
            }
            queue.enqueue(playlists)
            onStarted()
        }
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "playlist.csv"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(sharedLink: String?) {
    val vm: ImportViewModel = hiltViewModel()
    val nav = LocalNav.current
    val context = LocalContext.current
    val error by vm.error.collectAsState()
    var link by remember { mutableStateOf(sharedLink?.let(vm::cleanLink) ?: "") }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importFiles(uris) { nav.navigate(Routes.IMPORT_PROGRESS) }
    }
    val jobs by vm.jobs.collectAsState()
    val running = jobs.any { it.state == "queued" || it.state == "running" }
    val checking by vm.checking.collectAsState()
    val cutWarning by vm.cutWarning.collectAsState()

    cutWarning?.let { pending ->
        AlertDialog(
            onDismissRequest = { vm.cutWarning.value = null },
            title = { Text("Big playlist") },
            text = { Text("Detected more than 100 songs, please use Exportify or the playlist will be cut.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.cutWarning.value = null
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://exportify.net")))
                }) { Text("Use Exportify") }
            },
            dismissButton = {
                TextButton(onClick = { vm.importLink(pending, force = true) { nav.navigate(Routes.IMPORT_PROGRESS) } }) {
                    Text("Import first 100")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Bring your music") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    "Every song gets matched against YouTube, SoundCloud, Bandcamp and the open sources, in that order. " +
                        "anything that can't be found is listed so you can fix it by hand.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (running) item {
                FilledTonalButton(onClick = { nav.navigate(Routes.IMPORT_PROGRESS) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Moving playlists in the background, see progress")
                }
            }
            item {
                ServiceCard(R.drawable.ic_spotify, "Playlist link", Color(0xFF1DB954)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.ic_apple_music), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Spotify, Apple Music or YouTube Music, the playlist has to be public. Share → copy link, or share it straight to Stash.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = link, onValueChange = { link = it }, singleLine = true,
                        placeholder = { Text("https://open.spotify.com/playlist/…") },
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        enabled = link.isNotBlank() && !checking,
                        onClick = {
                            // youtube (music) playlists are already youtube songs: open them, nothing to match
                            val ytList = Regex("""(?:youtube\.com|youtu\.be)\S*[?&]list=([A-Za-z0-9_-]+)""").find(link)?.groupValues?.get(1)
                            if (ytList != null) nav.navigate(Routes.ytPlaylist("VL$ytList"))
                            else vm.importLink(vm.cleanLink(link)) { nav.navigate(Routes.IMPORT_PROGRESS) }
                        },
                    ) {
                        if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Import")
                    }
                    Text(
                        "Spotify links stop at the first 100 songs, use Exportify below for bigger ones.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            item {
                ServiceCard(R.drawable.ic_spotify, "Exportify", Color(0xFF1DB954)) {
                    Text(
                        "For private playlists, liked songs, or everything at once: open exportify.net, log in with Spotify, " +
                            "hit export (or export all for a ZIP), then pick the files here.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://exportify.net")))
                        }) { Text("Open Exportify") }
                        FilledTonalButton(
                            enabled = !checking,
                            onClick = { pickFiles.launch(arrayOf("text/*", "application/zip", "application/octet-stream")) },
                        ) { Text("Pick CSV / ZIP") }
                    }
                }
            }
            item { PlaylistCoversCard() }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        }
    }
}

@Composable
private fun ServiceCard(logo: Int, name: String, brand: Color, content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = MaterialTheme.shapes.medium, color = brand.copy(alpha = 0.14f), modifier = Modifier.size(52.dp)) {
                    Box(contentAlignment = Alignment.Center) { Image(painterResource(logo), name, Modifier.size(30.dp)) }
                }
                Spacer(Modifier.width(14.dp))
                Text(name, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportProgressScreen() {
    val actions = LocalActions.current
    val vm: ImportViewModel = hiltViewModel()
    val nav = LocalNav.current
    val jobs by vm.jobs.collectAsState()
    val missing by vm.missing.collectAsState()
    var showAllMissing by remember { mutableStateOf(false) }
    val active = jobs.filter { it.state == "queued" || it.state == "running" }
    val done = jobs.count { it.state == "done" }
    val songsDone = jobs.sumOf { it.done }
    val songsTotal = jobs.filter { it.state != "cancelled" }.sumOf { it.total }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (active.isEmpty()) "All moved" else "Moving playlists") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (active.isNotEmpty()) TextButton(onClick = { vm.queue.cancelAll() }) { Text("Stop all") }
                    else if (jobs.isNotEmpty()) TextButton(onClick = { vm.queue.clearFinished() }) { Text("Clear") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(24.dp)) {
                        Text("$done of ${jobs.count { it.state != "cancelled" }} playlists", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { if (songsTotal > 0) songsDone.toFloat() / songsTotal else 0f },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("$songsDone of $songsTotal songs checked", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "This keeps going in the background, even if you close Stash. The notification shows where it's at.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            // shows up by itself as soon as the first song isn't found
            if (missing.isNotEmpty()) {
                item(key = "missing-header") {
                    Column(Modifier.padding(top = 14.dp, start = 4.dp)) {
                        Text("Couldn't find (${missing.size})", style = MaterialTheme.typography.titleMedium)
                        RescanStatus(Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        Text(
                            "Tap one to search for it yourself. It goes back to the same spot in the playlist.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(if (showAllMissing) missing else missing.take(5), key = { "m:${it.id}" }) { u ->
                    val where = if (u.playlistId == LIKED_PLAYLIST_ID) "Liked songs"
                    else jobs.firstOrNull { it.playlistId == u.playlistId }?.name ?: ""
                    ListItem(
                        headlineContent = { Text(u.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(listOf(u.artist, where).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = { Icon(Icons.AutoMirrored.Rounded.HelpOutline, null, tint = MaterialTheme.colorScheme.outline) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { nav.navigate(Routes.fix(u.id, "${u.artist} ${u.title}")) },
                    )
                }
                if (missing.size > 5) item(key = "missing-more") {
                    TextButton(onClick = { showAllMissing = !showAllMissing }) {
                        Text(if (showAllMissing) "Show less" else "Show all ${missing.size}")
                    }
                }
            }
            item(key = "jobs-header") {
                Text("Playlists", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp, start = 4.dp))
            }
            items(jobs.asReversed(), key = { it.id }) { job ->
                val missing = job.done - job.matched
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = job.state != "cancelled") {
                        when {
                            job.intoLiked -> nav.navigate(Routes.LIKED)
                            job.playlistId != null -> nav.navigate(Routes.playlist(job.playlistId))
                        }
                    },
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (job.intoLiked) {
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Rounded.Favorite, null, Modifier.padding(12.dp))
                            }
                        } else Artwork(job.coverUrl, Modifier.size(48.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (job.intoLiked) "${job.name} → liked songs" else job.name,
                                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                when (job.state) {
                                    "queued" -> "Waiting · ${job.total} songs"
                                    "running" -> "${job.done} of ${job.total} · ${job.matched} found"
                                    "cancelled" -> "Stopped"
                                    else -> if (missing > 0) "${job.matched} found · $missing couldn't match" else "All ${job.matched} found"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (job.state == "done" && missing > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (job.state == "running") {
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { if (job.total > 0) job.done.toFloat() / job.total else 0f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        when (job.state) {
                            "queued", "running" -> IconButton(onClick = { vm.queue.cancel(job.id) }) { Icon(Icons.Rounded.Close, "Stop") }
                            "done" -> Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            else -> Unit
                        }
                    }
                }
            }

        }
    }
}
