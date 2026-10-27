package app.stash.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import app.stash.data.Library
import app.stash.recs.Covers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class PlaylistCoverViewModel @Inject constructor(val covers: Covers, val library: Library) : ViewModel()

/** copies a picked photo into app storage, so it stays when the gallery copy is deleted */
private suspend fun keepPhoto(context: Context, playlistId: Long, uri: Uri): String? = withContext(Dispatchers.IO) {
    runCatching {
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        // a new name every time, so the image cache doesn't keep showing the old picture
        dir.listFiles { f -> f.name.startsWith("playlist-$playlistId-") }?.forEach { it.delete() }
        val out = File(dir, "playlist-$playlistId-${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
        Uri.fromFile(out).toString()
    }.getOrNull()
}

/**
 * "change cover": a photo from the phone, or the cover of a spotify playlist link (custom
 * playlist covers included; exportify's csv files don't carry them), or back to the first song's.
 */
@Composable
fun PlaylistCoverDialog(playlistId: Long, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val vm: PlaylistCoverViewModel = hiltViewModel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = keepPhoto(context, playlistId, uri)
            if (saved != null) vm.library.setPlaylistCover(playlistId, saved) else onMessage("Couldn't read that photo")
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change cover") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Choose a photo") }
                OutlinedTextField(
                    value = link, onValueChange = { link = it }, singleLine = true,
                    label = { Text("Or paste the Spotify playlist link") },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { vm.library.setPlaylistCover(playlistId, null); onDismiss() }) {
                    Text("Use the first song's cover")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = link.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val cover = vm.covers.spotifyLink(link)
                        busy = false
                        if (cover != null) {
                            vm.library.setPlaylistCover(playlistId, cover)
                            onDismiss()
                        } else onMessage("Couldn't get a cover from that link")
                    }
                },
            ) { Text(if (busy) "Getting cover…" else "Use link") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
