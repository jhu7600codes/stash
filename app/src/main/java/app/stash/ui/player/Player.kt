package app.stash.ui.player

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.text.withStyle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import app.stash.data.calm
import app.stash.data.db.DownloadState
import app.stash.player.PlayerUiState
import app.stash.ui.LocalActions
import app.stash.ui.components.Artwork
import app.stash.ui.components.rememberArtColors
import app.stash.ui.components.TrackOptionsSheet
import app.stash.ui.components.formatDuration
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.delay

/** polls the controller position while on screen; media3 doesn't push position updates */
@Composable
private fun rememberPosition(everyMs: Long = 500): Pair<Long, Long> {
    val player = LocalActions.current.player
    var pos by remember { mutableLongStateOf(player.positionMs) }
    var buf by remember { mutableLongStateOf(player.bufferedMs) }
    LaunchedEffect(Unit) {
        while (true) {
            pos = player.positionMs
            buf = player.bufferedMs
            delay(everyMs)
        }
    }
    return pos to buf
}

/**
 * spotify style: a card tinted with the cover's color, title / artist, a heart and play, and a
 * thin progress line along the bottom edge
 */
@Composable
fun MiniPlayer(state: PlayerUiState, onOpen: () -> Unit) {
    val actions = LocalActions.current
    val scheme = MaterialTheme.colorScheme
    val (dominant, _) = rememberArtColors(state.artworkUri)
    // darkened like spotify's, so white text always reads; theme color until the cover loads
    val bg by animateColorAsState(
        dominant?.let { Color.Black.copy(alpha = 0.45f).compositeOver(it) } ?: scheme.secondaryContainer, label = "mini",
    )
    val fg = if (bg.luminance() < 0.45f) Color.White else Color.Black
    val track by remember(state.currentId) { actions.library.tracks.observe(state.currentId ?: "") }.collectAsState(initial = null)
    Surface(
        color = bg,
        contentColor = fg,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clickable(onClick = onOpen),
    ) {
        Column {
            Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(state.artworkUri, Modifier.size(40.dp), MaterialTheme.shapes.extraSmall)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        state.error?.let { "Can't play: $it" } ?: state.artist,
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (state.error != null) scheme.error else fg.copy(alpha = 0.7f),
                    )
                }
                track?.let { t ->
                    app.stash.ui.components.HeartButton(
                        t, onColor = scheme.primary.takeIf { bg.luminance() > 0.2f } ?: scheme.inversePrimary, offColor = fg,
                    )
                }
                IconButton(onClick = { actions.player.togglePlay() }) {
                    if (state.isBuffering && !state.isPlaying) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = fg)
                    } else {
                        Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", Modifier.size(30.dp))
                    }
                }
            }
            MiniProgress(state.durationMs, fg)
        }
    }
}

/** its own composable so the twice-a-second position updates only redraw this line */
@Composable
private fun MiniProgress(durationMs: Long, color: Color) {
    val (pos, _) = rememberPosition()
    LinearProgressIndicator(
        progress = { if (durationMs > 0) (pos.toFloat() / durationMs).coerceIn(0f, 1f) else 0f },
        modifier = Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 8.dp),
        color = color,
        trackColor = color.copy(alpha = 0.25f),
        drawStopIndicator = {},
        gapSize = 0.dp,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(state: PlayerUiState, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (dominant, vibrant) = rememberArtColors(state.artworkUri)
    val base = scheme.surface
    val top by animateColorAsState((dominant?.copy(alpha = 0.55f)?.compositeOver(base)) ?: scheme.surfaceContainerHigh, label = "top")
    val accent by animateColorAsState(
        vibrant?.let { v -> if (base.luminance() < 0.5f && v.luminance() < 0.25f) v.copy(alpha = 0.6f).compositeOver(Color.White) else v }
            ?: scheme.primary,
        label = "accent",
    )
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showRelated by remember { mutableStateOf(false) }

    // the whole screen, edge to edge (a bottom sheet left a gap at the top and rounded corners
    // showing the app behind). back or a drag down closes it
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    // back from the queue / lyrics / similar songs goes to the player first
    androidx.activity.compose.BackHandler(enabled = showQueue || showLyrics || showRelated) {
        showQueue = false; showLyrics = false; showRelated = false
    }
    var dragY by remember { mutableFloatStateOf(0f) }
    val settle by animateFloatAsState(dragY, label = "drag")
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { translationY = settle.coerceAtLeast(0f) }
            .draggable(
                orientation = androidx.compose.foundation.gestures.Orientation.Vertical,
                state = androidx.compose.foundation.gestures.rememberDraggableState { dragY = (dragY + it).coerceAtLeast(0f) },
                onDragStopped = { velocity -> if (dragY > 300f || velocity > 2500f) onDismiss() else dragY = 0f },
            )
            // swallow taps so they don't reach the app underneath
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        // lyrics sit on the cover's own color, like spotify's lyrics card; dark enough for white text
        val lyricsBg by animateColorAsState(
            dominant?.let { d -> androidx.compose.ui.graphics.lerp(d, Color.Black, if (d.luminance() > 0.4f) 0.55f else 0.3f) }
                ?: scheme.surfaceContainerHigh,
            label = "lyricsBg",
        )
        val bg by animateColorAsState(if (showLyrics) lyricsBg else base, androidx.compose.animation.core.tween(320), label = "bg")
        val artAlpha by animateFloatAsState(if (showLyrics) 0f else 1f, androidx.compose.animation.core.tween(320), label = "artAlpha")
        Box(
            Modifier.fillMaxSize().background(bg)
        ) {
            if (artAlpha > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = artAlpha }) {
                // the cover itself, blurred big behind everything (android 12+ can blur), then the color wash
                if (android.os.Build.VERSION.SDK_INT >= 31 && state.artworkUri != null) coil.compose.AsyncImage(
                    model = state.artworkUri, contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(70.dp).graphicsLayer { alpha = 0.6f },
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(top.copy(alpha = 0.55f), base.copy(alpha = 0.8f), base))
                    )
                )
            }
            // no bottom sheet anymore to set the text color: without this, text and icons came out black
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides scheme.onSurface) {
            // lyrics / queue / similar songs rise in over the player and sink back out
            val panel = when {
                showQueue -> 1
                showLyrics -> 2
                showRelated -> 3
                else -> 0
            }
            androidx.compose.animation.AnimatedContent(
                targetState = panel,
                transitionSpec = {
                    val ease = androidx.compose.animation.core.FastOutSlowInEasing
                    if (targetState != 0) {
                        (androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(320, easing = ease)) { it / 5 } +
                            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240))) togetherWith
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160))
                    } else {
                        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240, delayMillis = 60)) togetherWith
                            (androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(280, easing = ease)) { it / 5 } +
                                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)))
                    }
                },
                label = "panel",
            ) { which ->
                when (which) {
                    1 -> QueueView(state, accent, onClose = { showQueue = false })
                    2 -> LyricsView(state, lyricsBg, onClose = { showLyrics = false })
                    3 -> RelatedView(state, accent, onClose = { showRelated = false })
                    else -> NowPlayingContent(
                        state, accent, onCollapse = onDismiss, onQueue = { showQueue = true }, onLyrics = { showLyrics = true },
                        onRelated = { showRelated = true },
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun NowPlayingContent(
    state: PlayerUiState,
    accent: Color,
    onCollapse: () -> Unit,
    onQueue: () -> Unit,
    onLyrics: () -> Unit,
    onRelated: () -> Unit,
) {
    val actions = LocalActions.current
    val nav = app.stash.ui.LocalNav.current
    val player = actions.player
    val trackFlow = remember(state.currentId) { actions.library.tracks.observe(state.currentId ?: "").calm() }
    val track by trackFlow.collectAsState(initial = null)
    val (pos, buffered) = rememberPosition()
    var scrubbing by remember { mutableStateOf(false) }
    var scrub by remember { mutableFloatStateOf(0f) }
    var menu by remember { mutableStateOf(false) }
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White

    // expressive bits: the art breathes with play/pause, the play button morphs circle -> squircle
    val artScale by animateFloatAsState(
        if (state.isPlaying) 1f else 0.86f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "art",
    )
    val playCorner by animateDpAsState(if (state.isPlaying) 28.dp else 48.dp, label = "corner")

    // tablets / landscape: the cover on the left, everything else on the right
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp && it.screenWidthDp >= 600 }
    Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
    if (wide) Box(Modifier.weight(1f).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Artwork(state.artworkUri, Modifier.fillMaxSize(0.9f).aspectRatio(1f).scale(artScale), MaterialTheme.shapes.extraLarge)
    }
    Column(
        Modifier.weight(1f).fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCollapse) { Icon(Icons.Rounded.KeyboardArrowDown, "Close") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Now playing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                track?.album?.let {
                    Text(
                        it, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable {
                            onCollapse()
                            nav.navigate(app.stash.ui.Routes.album(it, track?.artist.orEmpty()))
                        },
                    )
                }
            }
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
        }

        Spacer(Modifier.weight(0.6f))
        if (!wide) {
            Artwork(
                state.artworkUri,
                Modifier.fillMaxWidth().aspectRatio(1f).scale(artScale),
                MaterialTheme.shapes.extraLarge,
            )
            Spacer(Modifier.weight(0.6f))
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    state.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1,
                    modifier = Modifier.basicMarquee(),
                )
                // tap the artist to go to their page (the first one, when there are several)
                Text(
                    state.artist, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(MaterialTheme.shapes.extraSmall).clickable(enabled = state.artist.isNotBlank()) {
                        onCollapse()
                        nav.navigate(app.stash.ui.Routes.artist(app.stash.providers.Matcher.primaryArtist(state.artist)))
                    },
                )
            }
            track?.let { t ->
                IconButton(onClick = {
                    when (t.downloadState) {
                        DownloadState.DONE -> actions.toast("Already downloaded")
                        DownloadState.NONE, DownloadState.FAILED -> actions.download(listOf(t))
                        else -> actions.toast("Already in the download queue")
                    }
                }) {
                    when (t.downloadState) {
                        DownloadState.DONE -> Icon(Icons.Rounded.DownloadDone, "Downloaded", tint = accent)
                        DownloadState.DOWNLOADING -> CircularProgressIndicator(
                            progress = { t.downloadProgress }, Modifier.size(22.dp), color = accent, strokeWidth = 2.5.dp,
                        )
                        else -> Icon(Icons.Rounded.Download, "Download")
                    }
                }
                app.stash.ui.components.HeartButton(t, onColor = accent, offColor = MaterialTheme.colorScheme.onSurface, size = 26.dp)
            }
        }

        if (state.viaWeb) Text(
            if (state.isBuffering) "Loading in the WebView (music.youtube.com)…" else "Playing through the WebView (music.youtube.com)",
            color = accent, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        state.error?.let {
            Text("Can't play this one: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp), maxLines = 2)
        }

        Spacer(Modifier.height(12.dp))
        val duration = state.durationMs.coerceAtLeast(1)
        SeekBar(
            fraction = if (scrubbing) scrub else (pos.toFloat() / duration).coerceIn(0f, 1f),
            buffered = (buffered.toFloat() / duration).coerceIn(0f, 1f),
            color = accent,
            onScrub = { scrubbing = true; scrub = it },
            onScrubEnd = { player.seekTo((scrub * duration).toLong()); scrubbing = false },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatDuration(if (scrubbing) (scrub * duration).toLong() else pos).ifEmpty { "0:00" },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (state.isBuffering) {
                Text(
                    if (buffered <= pos) "Loading…" else "Buffering…",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
            }
            Text(formatDuration(state.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { player.toggleShuffle() }) {
                Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (state.shuffle) accent else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledIconButton(
                onClick = { player.previous() }, modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            ) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(30.dp)) }
            FilledIconButton(
                onClick = { player.togglePlay() },
                modifier = Modifier.size(width = 96.dp, height = 80.dp),
                shape = RoundedCornerShape(playCorner),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = onAccent),
            ) {
                if (state.isBuffering && !state.isPlaying) CircularProgressIndicator(Modifier.size(32.dp), color = onAccent)
                else Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "play/pause", Modifier.size(40.dp))
            }
            FilledIconButton(
                onClick = { player.next() }, modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            ) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(30.dp)) }
            IconButton(onClick = { player.cycleRepeat() }) {
                Icon(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat",
                    tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.weight(0.4f))
        // apple music style: just three icons along the bottom
        Row(
            Modifier.fillMaxWidth().padding(bottom = 20.dp, start = 24.dp, end = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onLyrics, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.Lyrics, "Lyrics", Modifier.size(26.dp)) }
            IconButton(onClick = onRelated, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.AutoAwesome, "Similar songs", Modifier.size(26.dp)) }
            IconButton(onClick = onQueue, modifier = Modifier.size(52.dp)) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", Modifier.size(26.dp)) }
        }
    }
    }

    if (menu) track?.let { TrackOptionsSheet(it, onDismiss = { menu = false }) }
}

@Composable
private fun QueueView(state: PlayerUiState, accent: Color, onClose: () -> Unit) {
    val player = LocalActions.current.player
    val list = rememberLazyListState(initialFirstVisibleItemIndex = state.queueIndex.coerceAtLeast(0))
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Back") }
            Text("Queue", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text("${state.queue.size} songs", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 16.dp))
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f)) {
            itemsIndexed(state.queue, key = { i, item -> "$i:${item.mediaId}" }) { i, item ->
                val current = i == state.queueIndex
                Row(
                    Modifier.fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(if (current) accent.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable { player.skipTo(i) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Artwork(item.mediaMetadata.artworkUri, Modifier.size(48.dp))
                        if (current) Box(
                            Modifier.size(48.dp).clip(MaterialTheme.shapes.small).background(Color.Black.copy(alpha = 0.4f)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.GraphicEq, null, tint = Color.White) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.mediaMetadata.title?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (current) accent else MaterialTheme.colorScheme.onSurface)
                        Text(item.mediaMetadata.artist?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!current) IconButton(onClick = { player.removeAt(i) }) { Icon(Icons.Rounded.Close, "Remove") }
                }
            }
        }
    }
}

/**
 * live lyrics, spotify style: big bold lines on the cover's color, what's been sung white, what's
 * coming dimmed, the current line kept in view. tap a line to jump there. unsynced lyrics just scroll.
 */
@Composable
internal fun LyricsView(state: PlayerUiState, bg: Color, onClose: () -> Unit) {
    val actions = LocalActions.current
    val settings by actions.settings.collectAsState()
    val mxm = settings?.lyricsMusixmatch == true
    val translateOn = settings?.lyricsTranslate == true
    val lang = settings?.translateTo?.ifBlank { null } ?: java.util.Locale.getDefault().language
    var lyrics by remember(state.currentId) { mutableStateOf<app.stash.recs.SongLyrics?>(null) }
    var loading by remember(state.currentId) { mutableStateOf(true) }
    var translated by remember(state.currentId) { mutableStateOf<List<String?>?>(null) }
    var translating by remember(state.currentId) { mutableStateOf(false) }
    LaunchedEffect(state.currentId, mxm, translateOn, lang) {
        val id = state.currentId ?: return@LaunchedEffect
        loading = true
        val track = actions.library.tracks.get(id)
        lyrics = runCatching {
            actions.lyrics.forSong(
                state.title, state.artist, track?.durationMs?.takeIf { it > 0 } ?: state.durationMs,
                preferMusixmatch = mxm, translateTo = lang.takeIf { translateOn },
            )
        }.getOrNull()
        loading = false
    }
    LaunchedEffect(lyrics, translateOn, lang) {
        val l = lyrics
        translated = null
        if (!translateOn || l == null) return@LaunchedEffect
        translating = true
        translated = runCatching { actions.lyrics.translate(l, lang) }.getOrNull()
        translating = false
    }
    val (pos, _) = rememberPosition(everyMs = 200)
    val lines = lyrics?.lines.orEmpty()
    val current = remember(pos, lines) { lines.indexOfLast { it.first <= pos + 300 } }
    val list = rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0) list.animateScrollToItem((current - 2).coerceAtLeast(0))
    }
    val sung = Color.White
    val coming = Color.Black.copy(alpha = 0.5f)
    val big = MaterialTheme.typography.headlineMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, lineHeight = 36.sp)

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(state.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    state.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // translation on / off right here (also in settings)
            Surface(
                onClick = { actions.setLyricsTranslate(!translateOn) },
                shape = RoundedCornerShape(50),
                color = if (translateOn) Color.White else Color.Black.copy(alpha = 0.25f),
                contentColor = if (translateOn) Color.Black else Color.White,
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Translate, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (translating) "Translating…" else "Translate", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (translateOn && !translating && translated == null && lyrics != null) Text(
            "No translation for this one",
            style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> LyricsPlaceholder()
                lyrics == null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Lyrics, null, Modifier.size(48.dp), tint = Color.White.copy(alpha = 0.6f))
                    Spacer(Modifier.height(12.dp))
                    Text("No lyrics for this one", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text(
                        "Neither LRCLIB nor Musixmatch has it, or they didn't answer in time.",
                        style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
                    )
                }
                lines.isEmpty() -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 80.dp),
                ) {
                    val plainLines = lyrics!!.plain.orEmpty().lines()
                    items(plainLines.size) { i ->
                        Text(plainLines[i], style = big, color = sung)
                        translated?.getOrNull(i)?.let {
                            Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.7f))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                else -> LazyColumn(
                    state = list, modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 280.dp),
                ) {
                    items(lines.size) { i ->
                        val (at, text) = lines[i]
                        val color by animateColorAsState(if (i <= current) sung else coming, label = "line")
                        Column(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                                .clickable { actions.player.seekTo(at) }.padding(vertical = 8.dp)
                        ) {
                            // the line being sung lights up letter by letter (apple music style):
                            // musixmatch's word timing when it has it, else spread over the line
                            if (i == current && text.isNotBlank()) {
                                val end = lines.getOrNull(i + 1)?.first ?: (at + 6000)
                                val pieces = remember(lyrics, i) {
                                    lyrics?.words?.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: estimateWords(text, at, end)
                                }
                                KaraokeLine(pieces, end, big, sung, coming) { actions.player.positionMs }
                            } else Text(text.ifBlank { "♪" }, style = big, color = color)
                            translated?.getOrNull(i)?.let { tr ->
                                Text(tr, style = MaterialTheme.typography.titleMedium, color = color.copy(alpha = color.alpha * 0.75f))
                            }
                        }
                    }
                }
            }
            // soft fade at the top and bottom edges, so lines slide in instead of being cut
            Box(Modifier.fillMaxWidth().height(24.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(bg, Color.Transparent))))
        }
        lyrics?.source?.let {
            Text(
                "Lyrics from $it", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            )
        }
    }
}

/** pulsing bars where the lines will be, instead of a lone spinner */
@Composable
private fun LyricsPlaceholder() {
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse").animateFloat(
        0.12f, 0.28f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(800), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "a",
    )
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        listOf(0.8f, 0.6f, 0.9f, 0.5f, 0.75f, 0.65f, 0.85f).forEach { w ->
            Box(Modifier.fillMaxWidth(w).height(26.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = pulse)))
        }
    }
}

/** "similar songs": youtube music's radio for what's playing, play one or add them all */
@Composable
private fun RelatedView(state: PlayerUiState, accent: Color, onClose: () -> Unit) {
    val actions = LocalActions.current
    var songs by remember(state.currentId) { mutableStateOf<List<app.stash.data.db.Track>?>(null) }
    LaunchedEffect(state.currentId) {
        val id = state.currentId ?: return@LaunchedEffect
        val track = actions.library.tracks.get(id) ?: return@LaunchedEffect
        songs = runCatching { actions.similarTo(track) }.getOrDefault(emptyList())
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Similar to", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val list = songs
            if (!list.isNullOrEmpty()) androidx.compose.material3.TextButton(onClick = { actions.addToQueue(list) }) { Text("Add all", color = accent) }
        }
        val list = songs
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) }
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Couldn't find similar songs", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
                items(list.size) { i ->
                    val t = list[i]
                    app.stash.ui.components.TrackRow(
                        t, onClick = { actions.playNext(t); actions.player.next() },
                        trailing = {
                            IconButton(onClick = { actions.addToQueue(listOf(t)) }) {
                                Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * a thin seek bar: played part in the cover's color, buffered part lighter, a small round thumb
 * that grows while dragging. tap anywhere to jump there.
 */
@Composable
private fun SeekBar(fraction: Float, buffered: Float, color: Color, onScrub: (Float) -> Unit, onScrubEnd: () -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    val thumb by androidx.compose.animation.core.animateDpAsState(if (dragging) 16.dp else 12.dp, label = "thumb")
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val loaded = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    Box(
        Modifier.fillMaxWidth().height(32.dp)
            .pointerInput(Unit) {
                detectTapGestures { o -> onScrub((o.x / size.width).coerceIn(0f, 1f)); onScrubEnd() }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = true; onScrub((o.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = { dragging = false; onScrubEnd() },
                    onDragCancel = { dragging = false; onScrubEnd() },
                ) { change, _ -> onScrub((change.position.x / size.width).coerceIn(0f, 1f)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(thumb)) {
            val y = size.height / 2
            val h = 4.dp.toPx()
            val cap = androidx.compose.ui.graphics.StrokeCap.Round
            drawLine(track, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), h, cap)
            drawLine(loaded, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width * buffered, y), h, cap)
            drawLine(color, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width * fraction, y), h, cap)
            drawCircle(color, radius = size.height / 2, center = androidx.compose.ui.geometry.Offset(size.width * fraction, y))
        }
    }
}

/**
 * the sung line, lit up as it goes: every letter fades from dim to bright at its own moment
 * (a word's letters spread across the word's time), read off the player every frame so the
 * sweep is smooth. [pieces] are (start ms, text), spaces included.
 */
@Composable
private fun KaraokeLine(
    pieces: List<Pair<Long, String>>, lineEnd: Long, style: androidx.compose.ui.text.TextStyle,
    sung: Color, coming: Color, position: () -> Long,
) {
    var pos by remember { mutableLongStateOf(position()) }
    LaunchedEffect(Unit) { while (true) androidx.compose.runtime.withFrameMillis { pos = position() } }
    val text = androidx.compose.ui.text.buildAnnotatedString {
        pieces.forEachIndexed { w, (start, piece) ->
            val end = pieces.getOrNull(w + 1)?.first ?: lineEnd
            val perChar = ((end - start).coerceAtLeast(1) / piece.length.coerceAtLeast(1)).coerceAtLeast(1)
            piece.forEachIndexed { k, ch ->
                val t0 = start + perChar * k
                // each letter takes a little longer than its own slice to brighten, so it glows in
                val p = ((pos - t0).toFloat() / (perChar * 2.5f).coerceIn(80f, 400f)).coerceIn(0f, 1f)
                withStyle(androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.lerp(coming, sung, p))) { append(ch) }
            }
        }
    }
    Text(text, style = style)
}

/** no word timing (lrclib): spread the words over the line, longer words taking longer */
private fun estimateWords(text: String, start: Long, end: Long): List<Pair<Long, String>> {
    // sung a bit faster than the gap to the next line, which often holds a pause
    val span = ((end - start) * 0.85).toLong().coerceIn(600, 8000)
    val pieces = Regex("""\S+\s*""").findAll(text).map { it.value }.toList().ifEmpty { listOf(text) }
    val total = pieces.sumOf { it.trim().length.coerceAtLeast(1) }.toDouble()
    var t = start.toDouble()
    return pieces.map { w ->
        (t.toLong() to w).also { t += span * w.trim().length.coerceAtLeast(1) / total }
    }
}
