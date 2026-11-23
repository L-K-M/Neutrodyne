// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One debounced scheduler trigger. Each [signal] restarts a [quietMs] window, and [action] runs
 * once the window passes without another signal. A window that ends while [action] is still
 * running queues one more run after it instead of cancelling the run in progress: a round's own
 * writes signal its trigger (an outbox round deletes the rows it pushed), so cancelling there
 * would stop rounds partway through, after their push but before their pull and bookkeeping.
 */
internal class CoalescedTrigger(
    private val scope: CoroutineScope,
    private val quietMs: Long,
    private val action: suspend () -> Unit,
) {
    /** Guards the three fields below; never held across [action]. */
    private val lock = Mutex()
    private var window: Job? = null
    private var running: Job? = null
    private var queued = false

    /** Restarts the quiet window. A run in progress is left alone. */
    suspend fun signal() {
        lock.withLock {
            window?.cancel()
            window =
                scope.launch {
                    delay(quietMs)
                    fire()
                }
        }
    }

    /**
     * Drops the waiting window and a queued run, and cancels a run in progress unless [self] is
     * that run (an unlink from inside the round, after a revoked token).
     */
    suspend fun cancel(self: Job?) {
        lock.withLock {
            window?.takeIf { it != self }?.cancel()
            window = null
            queued = false
            val current = running ?: return@withLock
            if (current == self) return@withLock
            current.cancel()
            running = null
        }
    }

    private suspend fun fire() {
        lock.withLock {
            if (running?.isActive == true) {
                queued = true
                return@withLock
            }
            // A run that threw skipped its tail and may have left a request queued; this
            // fresh run serves it, so it must not run a second time.
            queued = false
            running = scope.launch { runQueued() }
        }
    }

    private suspend fun runQueued() {
        while (true) {
            action()
            val again =
                lock.withLock {
                    if (!queued) running = null
                    queued.also { queued = false }
                }
            if (!again) return
        }
    }
}
