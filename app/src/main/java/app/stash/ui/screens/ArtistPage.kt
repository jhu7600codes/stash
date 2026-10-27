package app.stash.ui.screens

import app.stash.data.calm
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stash.data.db.TrackDao
import app.stash.providers.ArtistDetails
import app.stash.providers.Matcher
import app.stash.providers.ProviderRegistry
import app.stash.ui.LocalActions
import app.stash.ui.LocalNav
import app.stash.ui.Routes
import app.stash.ui.components.ArtistBubble
import app.stash.ui.components.Artwork
import app.stash.ui.components.TrackRow
import app.stash.ui.components.compactCount
import app.stash.ui.components.originName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * one artist page for both cases: an artist picked in search (provider + id), or one from
 * your library (just a name; their page is looked up on youtube music, soundcloud, audius)
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ArtistViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val registry: ProviderRegistry,
    tracks: TrackDao,
) : ViewModel() {
    private val provider: String? = saved.get<String>("provider")?.ifBlank { null }
    private val id: String? = saved.get<String>("id")?.ifBlank { null }
    val fallbackName: String = saved.get<String>("name").orEmpty()
    val fallbackArt: String? = saved.get<String>("art")?.ifBlank { null }

    val details = MutableStateFlow<ArtistDetails?>(null)
    val loading = MutableStateFlow(true)
    private val name = MutableStateFlow(fallbackName)

    /** your own songs by them */
    val yours = name.flatMapLatest { tracks.observeArtist(it).calm() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            val found = runCatching {
                if (provider != null && id != null) registry.get(provider)?.artistDetails(id) else findByName(fallbackName)
            }.getOrNull()
            details.value = found
            if (found != null && fallbackName.isBlank()) name.value = found.name
            loading.value = false
        }
    }

    private suspend fun findByName(artist: String): ArtistDetails? {
        val want = Matcher.normalize(artist)
        for (pid in listOf("youtube", "soundcloud", "audius")) {
            val p = registry.get(pid) ?: continue
            val match = runCatching { p.searchArtists(artist, 5) }.getOrDefault(emptyList())
                .firstOrNull { Matcher.normalize(it.name) == want } ?: continue
            runCatching { p.artistDetails(match.id) }.getOrNull()?.let { return it }
        }
        return null
    }
}

@Composable
fun ArtistPageScreen() {
    val vm: ArtistViewModel = hiltViewModel()
    val actions = LocalActions.current
    val nav = LocalNav.current
    val uri = LocalUriHandler.current
    val d by vm.details.collectAsState()
    val loading by vm.loading.collectAsState()
    val yours by vm.yours.collectAsState()
    val currentId by actions.player.currentId.collectAsState()
    var showAll by remember { mutableStateOf(false) }
    var bioOpen by remember { mutableStateOf(false) }

    val name = d?.name ?: vm.fallbackName
    val popular = d?.popular.orEmpty()
    val playable = popular.ifEmpty { yours }
    val avatar = d?.avatarUrl ?: vm.fallbackArt ?: yours.firstNotNullOfOrNull { it.artworkUrl }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(320.dp)) {
                // banner (or a blown-up avatar when there isn't one), fading into the page
                Artwork(d?.bannerUrl ?: avatar, Modifier.fillMaxSize(), RectangleShape, Icons.Rounded.Person)
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.25f), 0.55f to Color.Transparent, 1f to MaterialTheme.colorScheme.surface)
                    )
                )
                IconButton(
                    onClick = { nav.popBackStack() },
                    modifier = Modifier.statusBarsPadding().padding(8.dp),
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.35f), contentColor = Color.White),
                ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Row(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
                    Artwork(
                        avatar,
                        Modifier.size(112.dp).border(3.dp, MaterialTheme.colorScheme.surface, CircleShape),
                        CircleShape, Icons.Rounded.Person,
                    )
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.padding(bottom = 6.dp)) {
                        Text(name, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val stats = listOfNotNull(
                            d?.followers?.let { "${compactCount(it)} fans" },
                            d?.listeners,
                            d?.location,
                        ).joinToString(" · ")
                        if (stats.isNotEmpty()) Text(stats, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if ((d?.followers ?: Long.MAX_VALUE) < 20_000) Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.extraSmall,
                            modifier = Modifier.padding(top = 4.dp),
                        ) { Text("Underground", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) }
                    }
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledIconButton(
                    onClick = { actions.play(playable) }, enabled = playable.isNotEmpty(),
                    modifier = Modifier.size(width = 88.dp, height = 56.dp), shape = MaterialTheme.shapes.large,
                ) { Icon(Icons.Rounded.PlayArrow, "Play", Modifier.size(30.dp)) }
                FilledTonalIconButton(onClick = { actions.shuffle(playable + yours) }, enabled = playable.isNotEmpty(), modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Rounded.Shuffle, "Shuffle")
                }
                FilledTonalIconButton(onClick = { playable.firstOrNull()?.let(actions::startRadio) }, enabled = playable.isNotEmpty(), modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Rounded.Radio, "Artist radio")
                }
                Spacer(Modifier.weight(1f))
                d?.link?.let { link ->
                    TextButton(onClick = { uri.openUri(link) }) {
                        Text(originName(d!!.provider))
                        Spacer(Modifier.size(4.dp))
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                    }
                }
            }
        }

        if (loading) item {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }

        d?.bio?.takeIf { it.isNotBlank() }?.let { bio ->
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(MaterialTheme.shapes.large).clickable { bioOpen = !bioOpen },
                ) {
                    Column(Modifier.padding(16.dp).animateContentSize()) {
                        Text("About", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(bio, style = MaterialTheme.typography.bodyMedium, maxLines = if (bioOpen) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        if (yours.isNotEmpty()) {
            item { SectionTitle("In your library") }
            itemsIndexed(yours, key = { _, t -> "y:${t.id}" }) { i, t ->
                TrackRow(t, onClick = { actions.play(yours, i) }, isCurrent = currentId == t.id)
            }
        }

        if (popular.isNotEmpty()) {
            item { SectionTitle("Popular") }
            val shown = if (showAll) popular else popular.take(10)
            itemsIndexed(shown, key = { _, t -> "p:${t.id}" }) { i, t ->
                TrackRow(t, onClick = { actions.play(popular, i) }, isCurrent = currentId == t.id, index = i)
            }
            if (popular.size > 10) item {
                TextButton(onClick = { showAll = !showAll }, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(if (showAll) "Show less" else "Show all ${popular.size}")
                }
            }
        }

        val similar = d?.similar.orEmpty()
        if (similar.isNotEmpty()) {
            item { SectionTitle("Fans also like") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(similar, key = { "${it.provider}:${it.id}" }) { a ->
                        ArtistBubble(a) { nav.navigate(Routes.remoteArtist(a)) }
                    }
                }
            }
        }

        if (!loading && d == null && yours.isEmpty()) item {
            Text(
                "Couldn't find $name anywhere right now",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp))
}
