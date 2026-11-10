// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Serializes an identity write with the engine's URL/token/state snapshot. The writer may
 * run a first-link push itself; its structured calls can read their own snapshot without
 * reacquiring the mutex. An inherited marker stops granting ownership once the hold ends.
 */
@Inject
@SingleIn(AppScope::class)
class SyncIdentityLock {
    private val mutex = Mutex()
    private val activeOwner = MutableStateFlow<Owner?>(null)

    internal suspend fun <T> withLock(block: suspend () -> T): T {
        val inherited = coroutineContext[Owner]
        if (inherited?.lock === this && activeOwner.value === inherited) return block()
        return mutex.withLock {
            val owner = Owner(this)
            activeOwner.value = owner
            try {
                // Return failures across the context boundary before rethrowing: coroutine
                // stack recovery must not replace the original cancellation or exception.
                withContext(owner) { runCatching { block() } }.getOrThrow()
            } finally {
                activeOwner.value = null
            }
        }
    }

    private class Owner(
        val lock: SyncIdentityLock,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Owner>
    }
}
