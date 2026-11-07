// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.domain.SyncActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The [SyncActivity] flag's implementation: a count of in-flight rounds, so overlapping
 * rounds (the periodic lane and a manual "Sync now") keep it active until the last one
 * ends. [SyncEngine.round] brackets its body with [begin]/[end], which also clears the
 * flag when a round fails or is cancelled.
 */
class SyncActivityState : SyncActivity {
    private val activeCount = MutableStateFlow(0)

    /** The in-flight round count; tests and diagnostics observe it. */
    val count: StateFlow<Int> = activeCount.asStateFlow()

    override val active: Boolean get() = activeCount.value > 0

    fun begin() {
        activeCount.update { it + 1 }
    }

    fun end() {
        activeCount.update { (it - 1).coerceAtLeast(0) }
    }
}
