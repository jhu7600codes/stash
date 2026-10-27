package app.stash.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transform

/**
 * room re-runs a query every time anything in its tables is written, and the importer, cover
 * fixer and downloads write all the time. without this every list on screen re-queried and
 * redrew several times a second while those ran, which is where the app-wide lag came from.
 * first value right away, then at most one every [ms], skipping ones that didn't change.
 */
fun <T> Flow<T>.calm(ms: Long = 400): Flow<T> =
    distinctUntilChanged().conflate().transform { emit(it); delay(ms) }
