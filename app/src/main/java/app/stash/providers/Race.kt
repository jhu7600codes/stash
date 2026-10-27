package app.stash.providers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * where the racers run. not a child of whoever asked: a blocking http read ignores cancel, and
 * a coroutineScope waits for every child, cancelled or not, before it returns. that's how a
 * NewPipe link found in 2s reached the player after 26s (yt2009's 25s timeout, still reading).
 * here the losers are cancelled and left to finish on their own.
 */
internal val raceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** starts [block] in [raceScope]; a failure is just null */
internal fun <T : Any> racer(block: suspend () -> T?): Deferred<T?> =
    raceScope.async { runCatching { block() }.getOrNull() }

/** the first non-null of [jobs] within [timeoutMs], or null when they all end empty or time runs out. never waits for the rest */
internal suspend fun <T : Any> firstNonNull(jobs: List<Deferred<T?>>, timeoutMs: Long = Long.MAX_VALUE): T? =
    withTimeoutOrNull(timeoutMs) {
        val pending = jobs.toMutableList()
        while (pending.isNotEmpty()) {
            val (done, value) = select<Pair<Deferred<T?>, T?>> { pending.forEach { d -> d.onAwait { d to it } } }
            if (value != null) return@withTimeoutOrNull value
            pending -= done
        }
        null
    }

/** races [tries], returns the first answer and cancels the others without waiting for them */
internal suspend fun <T : Any> race(tries: List<suspend () -> T?>, timeoutMs: Long = Long.MAX_VALUE): T? {
    val jobs = tries.map { racer(it) }
    try {
        return firstNonNull(jobs, timeoutMs)
    } finally {
        jobs.forEach { it.cancel() }
    }
}
