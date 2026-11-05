// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Which loaded items a remote mark-played has frozen (06 Sync interplay rule 3). One of the two
 * app-scoped playback-core exceptions: state lives in a [MutableStateFlow], so [freeze] and
 * [isFrozen] are safe from any thread — including a save transaction's `allowed` check, which
 * re-reads membership at commit time so a freeze can still reject an already queued save.
 *
 * The adapter lifts a freeze when the item stops being current ([currentChanged]) or becomes
 * unplayed here again; a frozen item keeps playing, its position writes stop, and the window
 * planner never advances it.
 */
@SingleIn(AppScope::class)
@Inject
class RemotePlayedGuard {
    private val frozenIds = MutableStateFlow<Set<Long>>(emptySet())

    val frozen: StateFlow<Set<Long>> = frozenIds.asStateFlow()

    fun isFrozen(episodeId: Long): Boolean = episodeId in frozenIds.value

    /** The save gate read inside each position transaction: `allowed()` must stay side-effect free. */
    fun allowsSaves(episodeId: Long): Boolean = !isFrozen(episodeId)

    /** 10's apply coroutine calls this before projecting the mark; callable from any thread. */
    fun freeze(episodeId: Long) {
        frozenIds.update { it + episodeId }
    }

    /** The item became unplayed here again; the next save writes normally. */
    fun lift(episodeId: Long) {
        frozenIds.update { it - episodeId }
    }

    /**
     * The player's current item changed ([episodeId] is null when nothing is loaded): every other
     * freeze lifts, because a freeze only ever governs the loaded item.
     */
    fun currentChanged(episodeId: Long?) {
        frozenIds.update { frozen ->
            if (episodeId != null && episodeId in frozen) setOf(episodeId) else emptySet()
        }
    }
}
