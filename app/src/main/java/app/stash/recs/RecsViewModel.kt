package app.stash.recs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stash.data.db.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** the home screen's recommendation rows */
@HiltViewModel
class RecsViewModel @Inject constructor(private val recommender: Recommender) : ViewModel() {
    private val _becauseYouLiked = MutableStateFlow<Pair<Track, List<Track>>?>(null)
    val becauseYouLiked: StateFlow<Pair<Track, List<Track>>?> = _becauseYouLiked
    val refreshing = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            _becauseYouLiked.value = runCatching { recommender.becauseYouLiked() }.getOrNull()
            recommender.refreshMixes()
        }
    }

    fun rebuildMixes() = viewModelScope.launch {
        refreshing.value = true
        runCatching { recommender.refreshMixes(forceIt = true) }
        _becauseYouLiked.value = runCatching { recommender.becauseYouLiked() }.getOrNull()
        refreshing.value = false
    }
}
