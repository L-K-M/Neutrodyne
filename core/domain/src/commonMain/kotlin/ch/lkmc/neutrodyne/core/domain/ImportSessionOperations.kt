// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive

/** Accepted report writes belong to the application, including while their route is absent. */
@SingleIn(AppScope::class)
class ImportSessionOperations
    @Inject
    constructor(
        @ApplicationScope private val appScope: CoroutineScope,
    ) {
        private val active = MutableStateFlow<Set<Long>>(emptySet())
        val busySessions = active.asStateFlow()

        /** Reject overlaps before dispatch, so an older probe cannot overwrite a newer write. */
        fun <T> start(
            sessionId: Long,
            block: suspend () -> T,
        ): Deferred<T>? {
            if (!appScope.coroutineContext.isActive) return null
            while (true) {
                val current = active.value
                if (sessionId in current) return null
                if (active.compareAndSet(current, current + sessionId)) break
            }

            return appScope.async { block() }.also { job ->
                // Completion also releases ownership when cancellation prevents dispatch.
                job.invokeOnCompletion { active.update { it - sessionId } }
            }
        }
    }
