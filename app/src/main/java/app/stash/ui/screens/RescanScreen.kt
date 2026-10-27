package app.stash.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stash.data.calm
import app.stash.data.db.LIKED_PLAYLIST_ID
import app.stash.data.db.PlaylistDao
import app.stash.ui.LocalActions
import app.stash.ui.LocalNav
import app.stash.ui.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class RescanViewModel @Inject constructor(playlists: PlaylistDao) : ViewModel() {
    val missing = playlists.observeAllUnmatched().calm().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}

/** "rescan failed songs" as its own screen: big progress, the song it's on, found and still missing */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RescanScreen() {
    val actions = LocalActions.current
    val nav = LocalNav.current
    val vm: RescanViewModel = hiltViewModel()
    val rescan by actions.rescan.collectAsState()
    val missing by vm.missing.collectAsState()
    val playlists by actions.playlists.collectAsState()
    var showAllMissing by remember { mutableStateOf(false) }
    val r = rescan
    val running = r?.running == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rescan failed songs") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "progress") {
                Card(
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                ) {
                    Column(Modifier.padding(24.dp)) {
                        when {
                            running && r != null -> {
                                Text(
                                    if (r.total == 0) "Getting ready…" else "${r.done} of ${r.total} checked",
                                    style = MaterialTheme.typography.headlineMedium,
                                )
                                Spacer(Modifier.height(14.dp))
                                LinearProgressIndicator(
                                    progress = { if (r.total > 0) r.done.toFloat() / r.total else 0f },
                                    modifier = Modifier.fillMaxWidth().height(10.dp),
                                )
                                Spacer(Modifier.height(10.dp))
                                Text("${r.found} found so far", style = MaterialTheme.typography.titleMedium)
                                AnimatedVisibility(r.current != null) {
                                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            "Looking for ${r.current.orEmpty()}", style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Text(
                                    "Keeps going in the background if you leave this screen.",
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp),
                                )
                                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(onClick = { actions.rescanMissing(restart = true) }) {
                                        Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Restart")
                                    }
                                    TextButton(onClick = { actions.stopRescan() }) { Text("Stop") }
                                }
                            }
                            else -> {
                                Text(
                                    when {
                                        missing.isEmpty() -> "Nothing missing"
                                        missing.size == 1 -> "1 song couldn't be found"
                                        else -> "${missing.size} songs couldn't be found"
                                    },
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                                if (r != null && r.total > 0) Text(
                                    "Last rescan found ${r.found} of ${r.total}",
                                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp),
                                )
                                Text(
                                    "Searches every source again for each one, and puts what it finds back in its spot.",
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
                                )
                                if (missing.isNotEmpty()) FilledTonalButton(
                                    onClick = { actions.rescanMissing() },
                                    modifier = Modifier.padding(top = 14.dp),
                                ) {
                                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (r != null && r.total > 0) "Rescan again" else "Rescan now")
                                }
                            }
                        }
                    }
                }
            }

            val found = r?.foundNames.orEmpty()
            if (found.isNotEmpty()) {
                item(key = "found-header") { SectionTitle(if (running) "Found (${r?.found})" else "Found last time (${r?.found})") }
                items(found, key = { "f:$it" }) { name ->
                    ListItem(
                        headlineContent = { Text(name.substringAfter(" - "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(name.substringBefore(" - "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }

            if (missing.isNotEmpty()) {
                item(key = "missing-header") {
                    Column {
                        SectionTitle("Still missing (${missing.size})")
                        Text(
                            "Tap one to search for it yourself.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                        )
                    }
                }
                items(if (showAllMissing) missing else missing.take(50), key = { "m:${it.id}" }) { u ->
                    val where = if (u.playlistId == LIKED_PLAYLIST_ID) "Liked songs"
                    else playlists.firstOrNull { it.id == u.playlistId }?.name.orEmpty()
                    ListItem(
                        headlineContent = { Text(u.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(listOf(u.artist, where).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = { Icon(Icons.AutoMirrored.Rounded.HelpOutline, null, tint = MaterialTheme.colorScheme.outline) },
                        trailingContent = { Icon(Icons.Rounded.Search, "Search for it", tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { nav.navigate(Routes.fix(u.id, "${u.artist} ${u.title}")) },
                    )
                }
                if (missing.size > 50 && !showAllMissing) item(key = "missing-more") {
                    TextButton(onClick = { showAllMissing = true }) { Text("Show all ${missing.size}") }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, start = 4.dp, bottom = 4.dp))
}
