// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Platform host; completion means the request has been durably enqueued. */
public interface ArtworkWorkScheduler {
    public suspend fun enqueue()
}

@SingleIn(AppScope::class)
@Inject
public class ArtworkSyncScheduler(
    private val work: ArtworkWorkScheduler,
    @param:ApplicationScope scope: CoroutineScope,
) {
    private val requests = MutableStateFlow(0L)

    init {
        scope.launch {
            requests.collectLatest { sequence ->
                if (sequence == 0L) return@collectLatest
                delay(DEBOUNCE_MS)
                suspendRunCatching { enqueueNow() }
                    .onFailure { Log.w("ArtworkSync", it) { "artwork enqueue failed; next trigger retries" } }
            }
        }
    }

    public fun request() {
        // CAS keeps concurrent import/subscription hints from losing the final debounce edge.
        while (true) {
            val previous = requests.value
            if (requests.compareAndSet(previous, previous + 1)) return
        }
    }

    /** Continuations bypass debounce and await the platform's durable enqueue. */
    public suspend fun enqueueNow() {
        work.enqueue()
    }

    private companion object {
        const val DEBOUNCE_MS = 2_000L
    }
}
