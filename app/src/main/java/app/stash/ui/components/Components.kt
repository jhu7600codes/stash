package app.stash.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import coil.imageLoader
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.stash.data.db.DownloadState
import app.stash.data.db.Track
import app.stash.providers.RemoteArtist
import coil.compose.AsyncImage

fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
fun Artwork(
    url: Any?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    icon: ImageVector = Icons.Rounded.MusicNote,
) {
    // icon underneath, picture drawn over it once loaded. plain AsyncImage, not the subcompose
    // version, which builds a whole extra layout per picture and made long lists stutter
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        if (url is app.stash.data.db.MixCover) {
            MixArt(url, Modifier.fillMaxSize())
            return@Box
        }
        // spotify covers come from i.scdn.co, which some networks block; the same picture is on
        // spotify's other cdn hosts, so try those before giving up
        val candidates = remember(url) { artCandidates(url) }
        var attempt by remember(url) { mutableIntStateOf(0) }
        if (url != null) AsyncImage(
            model = candidates.getOrNull(attempt) ?: url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onError = { if (attempt < candidates.lastIndex) attempt++ },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private val spotifyImage = Regex("""https?://(?:i\.scdn\.co|image-cdn-(?:ak|fa)\.spotifycdn\.com)/image/([A-Za-z0-9]+)""")

/** the url itself, plus mirrors for spotify covers */
internal fun artCandidates(url: Any?): List<Any> {
    if (url == null) return emptyList()
    val id = (url as? String)?.let { spotifyImage.find(it)?.groupValues?.get(1) } ?: return listOf(url)
    return listOf(
        url,
        "https://image-cdn-ak.spotifycdn.com/image/$id",
        "https://image-cdn-fa.spotifycdn.com/image/$id",
        "https://i.scdn.co/image/$id",
    ).distinct()
}

@Composable
fun DownloadBadge(track: Track, modifier: Modifier = Modifier) {
    when (track.downloadState) {
        DownloadState.DONE -> Icon(
            Icons.Rounded.DownloadDone, "Downloaded",
            modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary,
        )
        DownloadState.DOWNLOADING -> CircularProgressIndicator(
            progress = { track.downloadProgress },
            modifier = modifier.size(14.dp),
            strokeWidth = 2.dp,
        )
        DownloadState.QUEUED -> Icon(Icons.Rounded.Schedule, "queued", modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
        DownloadState.FAILED -> Icon(Icons.Rounded.ErrorOutline, "Download failed", modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
        DownloadState.NONE -> Unit
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    index: Int? = null,
    showArt: Boolean = true,
    showProvider: Boolean = false,
    /** extra bit for the subtitle, e.g. "12K plays · #phonk" in search */
    note: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null && !showArt) {
            Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
                if (isCurrent) Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                else Text("${index + 1}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            }
        }
        if (showArt) {
            Box {
                Artwork(track.artworkUrl, Modifier.size(52.dp), MaterialTheme.shapes.small)
                if (isCurrent) {
                    Box(
                        Modifier.size(52.dp).clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primaryContainer) }
                }
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DownloadBadge(track)
                val sub = buildList {
                    add(track.artist)
                    if (showProvider) add(track.provider)
                    formatDuration(track.durationMs).takeIf { it.isNotEmpty() }?.let(::add)
                    note?.let(::add)
                }.filter { it.isNotBlank() }.joinToString(" · ")
                Text(
                    sub,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Rounded.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (menu) TrackOptionsSheet(track = track, onDismiss = { menu = false })
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(88.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

/** big square-ish card for carousels (the google play music "Listen now" look) */
@Composable
fun CoverCard(
    title: String,
    subtitle: String?,
    artwork: Any?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 148.dp,
    icon: ImageVector = Icons.Rounded.MusicNote,
    shape: Shape = MaterialTheme.shapes.large,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        // no box around it, just the cover and its name, like spotify
        color = Color.Transparent,
        modifier = modifier.width(size + 16.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            Artwork(artwork, Modifier.size(size), shape, icon)
            Spacer(Modifier.height(8.dp))
            Text(
                title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            if (subtitle != null) Text(
                subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

val ListBottomPadding = PaddingValues(bottom = 24.dp)

/** "spotify" -> "Spotify" for labels like "From Spotify" */
fun originName(origin: String) = when (origin) {
    "spotify" -> "Spotify"
    "apple" -> "Apple Music"
    "youtube" -> "YouTube"
    "soundcloud" -> "SoundCloud"
    else -> origin.replaceFirstChar { it.uppercase() }
}

fun compactCount(n: Long): String = when {
    n >= 1_000_000_000 -> "%.1fB".format(n / 1e9)
    n >= 1_000_000 -> "%.1fM".format(n / 1e6)
    n >= 1_000 -> "%.0fK".format(n / 1e3)
    else -> "$n"
}.replace(".0", "")

@Composable
fun ArtistBubble(a: RemoteArtist, onClick: () -> Unit) {
    Column(
        Modifier.width(96.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Artwork(a.avatarUrl, Modifier.size(80.dp), CircleShape, Icons.Rounded.Person)
            if ((a.followers ?: Long.MAX_VALUE) < 20_000) Surface(
                color = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary,
                shape = MaterialTheme.shapes.extraSmall, modifier = Modifier.align(Alignment.BottomCenter),
            ) { Text("Underground", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp)) }
        }
        Spacer(Modifier.height(6.dp))
        Text(a.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(
            listOfNotNull(a.followers?.let { "${compactCount(it)} fans" }, originName(a.provider)).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}


/** "Rescan" button, or the live rescan progress / last result in its place */
@Composable
fun RescanStatus(modifier: Modifier = Modifier) {
    val actions = app.stash.ui.LocalActions.current
    val nav = app.stash.ui.LocalNav.current
    val rescan by actions.rescan.collectAsState()
    val r = rescan
    Column(modifier.clickable { nav.navigate(app.stash.ui.Routes.RESCAN) }) {
        when {
            r != null && r.running -> {
                Text(
                    if (r.total == 0) "Rescanning…" else "Rescanning: ${r.done} of ${r.total} checked · ${r.found} found",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                )
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { if (r.total > 0) r.done.toFloat() / r.total else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                if (r != null && r.total > 0) Text(
                    "Last rescan found ${r.found} of ${r.total}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                ) else Spacer(Modifier.weight(1f))
                androidx.compose.material3.FilledTonalButton(onClick = {
                    actions.rescanMissing()
                    nav.navigate(app.stash.ui.Routes.RESCAN)
                }) { Text("Rescan failed songs") }
            }
        }
    }
}


/** dominant + vibrant colors pulled from a cover, for tinted headers / players (spotify style) */
@Composable
fun rememberArtColors(art: Any?): Pair<Color?, Color?> {
    val url = if (art is app.stash.data.db.MixCover) art.photo ?: return mixAccent(art.mixId) to null else art
    val context = androidx.compose.ui.platform.LocalContext.current
    var colors by remember(url) { mutableStateOf<Pair<Color?, Color?>>(null to null) }
    androidx.compose.runtime.LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val result = context.imageLoader.execute(
            coil.request.ImageRequest.Builder(context).data(url).allowHardware(false).size(128).build()
        )
        val bitmap = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return@LaunchedEffect
        val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            androidx.palette.graphics.Palette.from(bitmap).generate()
        }
        val dominant = p.dominantSwatch?.rgb?.let { Color(it) }
        val vibrant = (p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.mutedSwatch)?.rgb?.let { Color(it) }
        colors = dominant to vibrant
    }
    return colors
}

/** spotify's liked songs tile: a gradient square with a heart, in this theme's colors */
@Composable
fun LikedArt(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.clip(MaterialTheme.shapes.small)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(scheme.primary, scheme.tertiary))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Favorite, null, Modifier.fillMaxSize(0.4f), tint = scheme.onPrimary)
    }
}
