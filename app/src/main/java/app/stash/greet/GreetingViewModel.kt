package app.stash.greet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stash.data.db.PlaylistDao
import app.stash.data.prefs.Settings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

/** one greeting per app open: time-based right away, swapped for a place one once it's known */
@HiltViewModel
class GreetingViewModel @Inject constructor(
    private val places: PlaceDetector,
    private val playlists: PlaylistDao,
    private val settings: Settings,
) : ViewModel() {
    // same seed for both passes so the title doesn't jump when the place comes in
    private val seed = Random.nextInt()
    private val _greeting = MutableStateFlow(Greetings.now(null, null, Random(seed)))
    val greeting: StateFlow<Greeting> = _greeting

    fun refresh() = viewModelScope.launch {
        val artist = runCatching { playlists.topArtist() }.getOrNull()
        val place = if (settings.current().locationGreetings) runCatching { places.detect() }.getOrNull() else null
        _greeting.value = Greetings.now(place, artist, Random(seed))
    }
}
