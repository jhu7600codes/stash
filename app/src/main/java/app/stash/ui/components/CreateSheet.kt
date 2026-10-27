package app.stash.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.stash.ui.LocalActions
import app.stash.ui.LocalNav
import app.stash.ui.Routes
import kotlinx.coroutines.launch

/** the bottom bar's create button: everything that makes or brings in something new, in one place */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateSheet(onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val missing by actions.missingCount.collectAsState()
    var naming by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text("Create", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
            Option(Icons.AutoMirrored.Rounded.PlaylistAdd, "Create a playlist", "Build one from songs you find") { naming = true }
            Option(Icons.Rounded.SwapHoriz, "Move playlists", "Bring them over from Spotify, Apple Music or a link") {
                onDismiss(); nav.navigate(Routes.import())
            }
            Option(Icons.Rounded.ManageSearch, "Find missing songs", if (missing > 0) "$missing songs couldn't be found yet" else "Nothing missing right now") {
                onDismiss(); nav.navigate(Routes.RESCAN)
            }
            Option(Icons.Rounded.Image, "Get playlist covers", "Copy your Spotify playlists' covers over") {
                onDismiss(); nav.navigate(Routes.COVER_GRAB)
            }
            Option(Icons.Rounded.Tv, "Pair a device", "Log a TV or tablet into your account with its code") { pairing = true }
        }
    }

    if (pairing) PairDeviceDialog(onDismiss = { pairing = false; onDismiss() })
    if (naming) NewPlaylistDialog(
        onDismiss = { naming = false },
        onCreate = { name ->
            scope.launch {
                val id = actions.library.createPlaylist(name)
                onDismiss()
                nav.navigate(Routes.playlist(id))
            }
        },
    )
}

@Composable
private fun Option(icon: ImageVector, title: String, about: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(about) },
        leadingContent = {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(48.dp)) {
                Icon(icon, null, Modifier.padding(12.dp))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 8.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
    )
}

/** type the code a tv / tablet shows; it gets logged into this account */
@Composable
fun PairDeviceDialog(onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Pair a device") },
        text = {
            Column {
                Text("Open Stash on the TV or tablet and type the code it shows. It logs into your account and gets your playlists.")
                androidx.compose.material3.OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.uppercase().filter { it.isLetterOrDigit() }.take(6); error = null },
                    label = { Text("Code") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    textStyle = MaterialTheme.typography.headlineSmall.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(6f, androidx.compose.ui.unit.TextUnitType.Sp)),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = code.length == 6 && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching { actions.pairing.claim(code) }
                            .onSuccess { name -> actions.toast("Paired $name, it's logging in now"); onDismiss() }
                            .onFailure { error = it.message ?: "Couldn't pair" }
                        busy = false
                    }
                },
            ) { Text(if (busy) "Pairing…" else "Pair") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
