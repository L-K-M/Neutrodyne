// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
 *
 * Entries count, because an apply transaction that reported a mark can still roll back: its
 * [release] then undoes only its own contribution, never a freeze a committed transaction owns.
 */
@SingleIn(AppScope::class)
@Inject
class RemotePlayedGuard {
    private val marks = MutableStateFlow<Map<Long, Int>>(emptyMap())

    /** The currently frozen episode ids. */
    val frozen: Flow<Set<Long>> = marks.map { it.keys }.distinctUntilChanged()

    fun isFrozen(episodeId: Long): Boolean = episodeId in marks.value

    /** The save gate read inside each position transaction: `allowed()` must stay side-effect free. */
    fun allowsSaves(episodeId: Long): Boolean = !isFrozen(episodeId)

    /** 10's apply coroutine calls this inside the applying transaction; callable from any thread. */
    fun freeze(episodeId: Long) {
        marks.update { it + (episodeId to (it[episodeId] ?: 0) + 1) }
    }

    /**
     * The transaction that called [freeze] rolled back: drop exactly its contribution. A freeze
     * installed by a transaction that committed stays.
     */
    fun release(episodeId: Long) {
        marks.update { frozen ->
            val count = frozen[episodeId] ?: return@update frozen
            if (count <= 1) frozen - episodeId else frozen + (episodeId to count - 1)
        }
    }

    /** The item became unplayed here again; the next save writes normally. */
    fun lift(episodeId: Long) {
        marks.update { it - episodeId }
    }

    /**
     * The player's current item changed ([episodeId] is null when nothing is loaded): every other
     * freeze lifts, because a freeze only ever governs the loaded item.
     */
    fun currentChanged(episodeId: Long?) {
        marks.update { frozen ->
            if (episodeId != null && episodeId in frozen) mapOf(episodeId to frozen.getValue(episodeId)) else emptyMap()
        }
    }
}
