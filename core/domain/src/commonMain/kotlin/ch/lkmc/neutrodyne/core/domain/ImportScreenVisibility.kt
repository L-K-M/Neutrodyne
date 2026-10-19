// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which import session screen is currently visible, if any (05 §8's "suppressed while that
 * session's screen is open"). The import route (D2) calls [enter]/[leave]; the report poster
 * reads [visibleSessionId] — a real visibility fact, not a global foreground guess. Default
 * is "nothing visible", which keeps notifications flowing until the route feeds it.
 */
@SingleIn(AppScope::class)
class ImportScreenVisibility
    @Inject
    constructor() {
        private val visible = MutableStateFlow<Long?>(null)

        val visibleSessionId: StateFlow<Long?> = visible.asStateFlow()

        fun enter(sessionId: Long) {
            visible.value = sessionId
        }

        /** [leave] only clears the session it names — a stale exit cannot hide a newer entry. */
        fun leave(sessionId: Long) {
            if (visible.value == sessionId) visible.value = null
        }
    }
