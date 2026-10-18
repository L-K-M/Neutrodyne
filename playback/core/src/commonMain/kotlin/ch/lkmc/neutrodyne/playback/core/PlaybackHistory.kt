// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.model.QueueItem
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface PreviousAction {
    /** Seek to zero as SeekOrigin.USER so the durable position is explicitly reset. */
    data object Restart : PreviousAction

    data class GoBack(
        val item: QueueItem,
    ) : PreviousAction
}

/** Process memory only. Preserve original origin so going back can restore a context anchor. */
@SingleIn(AppScope::class)
@Inject
class PlaybackHistory {
    private val entries = MutableStateFlow<List<QueueItem>>(emptyList())
    val items: StateFlow<List<QueueItem>> = entries.asStateFlow()

    /** Record the outgoing item only after a forward arrival transaction commits. */
    fun record(item: QueueItem) {
        entries.update { (it + item).takeLast(LIMIT) }
    }

    /** Peek first; a failed goBack must not consume history. */
    fun previous(positionMs: Long): PreviousAction {
        if (positionMs > RESTART_MS) return PreviousAction.Restart

        return entries.value.lastOrNull()?.let(PreviousAction::GoBack) ?: PreviousAction.Restart
    }

    fun consume(item: QueueItem) {
        entries.update { if (it.lastOrNull() == item) it.dropLast(1) else it }
    }

    fun clear() {
        entries.value = emptyList()
    }

    private companion object {
        const val LIMIT = 20
        const val RESTART_MS = 3_000L
    }
}
