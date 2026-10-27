package app.stash.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stash.data.db.Track
import app.stash.ui.LocalActions

/**
 * the heart, muzix style: it adds to whatever playlist you pick (liked songs by default) and is
 * filled when the song is in it. tap to add / remove; where it adds is picked in settings.
 */
@Composable
fun HeartButton(track: Track, onColor: Color, offColor: Color, size: androidx.compose.ui.unit.Dp = 24.dp) {
    val actions = LocalActions.current
    val hearted by remember(track.id) { actions.hearted(track.id) }.collectAsState(initial = track.liked)
    val target by actions.heartTarget.collectAsState()
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable { actions.toggleHeart(track, hearted) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (hearted) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (hearted) "Remove from ${target?.name ?: "liked songs"}" else "Add to ${target?.name ?: "liked songs"}",
            tint = if (hearted) onColor else offColor, modifier = Modifier.size(size),
        )
    }
}

/** "the heart adds to…": liked songs or one of your playlists */
@Composable
fun HeartTargetDialog(onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val playlists by actions.playlists.collectAsState()
    val target by actions.heartTarget.collectAsState()
    val own = playlists.filter { it.origin != "mix" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("The heart adds songs to") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    ListItem(
                        headlineContent = { Text("Liked songs") },
                        leadingContent = { LikedArt(Modifier.size(44.dp)) },
                        trailingContent = { if (target == null) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { actions.setHeartTarget(null); onDismiss() },
                    )
                }
                items(own, key = { it.id }) { p ->
                    ListItem(
                        headlineContent = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${p.trackCount} songs") },
                        leadingContent = { Artwork(p.art, Modifier.size(44.dp), MaterialTheme.shapes.extraSmall) },
                        trailingContent = { if (target?.id == p.id) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { actions.setHeartTarget(p); onDismiss() },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
