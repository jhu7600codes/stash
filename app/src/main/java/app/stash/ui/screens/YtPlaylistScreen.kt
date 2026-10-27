package app.stash.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stash.data.Library
import app.stash.providers.RemotePlaylist
import app.stash.providers.YouTubeMusicSearch
import app.stash.ui.LocalActions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class YtPlaylistViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val music: YouTubeMusicSearch,
    private val library: Library,
) : ViewModel() {
    private val id: String = saved["id"] ?: ""
    val playlist = MutableStateFlow<RemotePlaylist?>(null)
    val error = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            runCatching { music.playlist(id) }
                .onSuccess { if (it == null) error.value = "Couldn't open this playlist" else playlist.value = it }
                .onFailure { error.value = it.message ?: "Couldn't open this playlist" }
        }
    }

    /** a copy in your library, with its cover, to keep / download / edit */
    fun save(onDone: (String) -> Unit) {
        val p = playlist.value ?: return
        viewModelScope.launch {
            val newId = library.createPlaylist(p.title)
            library.addToPlaylist(newId, p.tracks).join()
            p.coverUrl?.let { library.setPlaylistCover(newId, it) }
            onDone("Saved ${p.title} to your library")
        }
    }
}

/** a youtube music playlist from search (or a pasted link): play it, download it, or save it */
@Composable
fun YtPlaylistScreen() {
    val vm: YtPlaylistViewModel = hiltViewModel()
    val actions = LocalActions.current
    val playlist by vm.playlist.collectAsState()
    val error by vm.error.collectAsState()
    val p = playlist
    if (p == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (error != null) Text(error!!) else CircularProgressIndicator()
        }
        return
    }
    CollectionScreen(
        title = p.title,
        kind = listOfNotNull("YouTube Music playlist", p.subtitle).joinToString(" · "),
        tracks = p.tracks,
        artwork = p.coverUrl ?: p.tracks.firstOrNull()?.artworkUrl,
        menu = listOf(MenuEntry("Save to your library", Icons.Rounded.LibraryAdd) { vm.save(actions::toast) }),
    )
}
