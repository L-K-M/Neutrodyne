// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public enum class EpisodeSelectionAction { MARK_PLAYED, MARK_UNPLAYED }

/** Screen-owned selection. Ranges and Select all use loaded IDs, never paging placeholders. */
public class EpisodeSelectionController(
    private val scope: CoroutineScope,
    private val setPlayed: suspend (List<Long>, Boolean) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val selected = MutableStateFlow<Set<Long>>(emptySet())
    public val ids = selected.asStateFlow()
    private val writing = MutableStateFlow(false)
    public val busy = writing.asStateFlow()
    private var anchor: Long? = null
    private var bound = false
    internal var generation: Int = 0
        private set

    internal fun bind(
        snapshot: List<Long?>,
        savedGeneration: Int,
    ) {
        if (!bound) generation = savedGeneration
        if (generation == savedGeneration) restore(snapshot)
        bound = true
    }

    internal fun leave() {
        clear()
        generation++
    }

    // The anchor may have been toggled off; save it independently of the selected IDs.
    internal fun snapshot(): List<Long?> = listOf(anchor) + selected.value

    public fun toggle(id: Long) {
        anchor = id
        selected.value = if (id in selected.value) selected.value - id else selected.value + id
    }

    public fun extend(
        id: Long,
        loadedIds: List<Long>,
    ) {
        val start = loadedIds.indexOf(anchor)
        val end = loadedIds.indexOf(id)
        if (start < 0 || end < 0) {
            toggle(id)
            return
        }
        selected.value += loadedIds.subList(minOf(start, end), maxOf(start, end) + 1)
    }

    public fun selectAll(loadedIds: List<Long>) {
        selected.value += loadedIds
    }

    public fun clear() {
        anchor = null
        selected.value = emptySet()
    }

    internal fun restore(snapshot: List<Long?>) {
        anchor = snapshot.firstOrNull()
        selected.value = snapshot.drop(1).filterNotNull().toSet()
    }

    public fun perform(action: EpisodeSelectionAction) {
        val ids = selected.value.toList()
        if (ids.isEmpty() || writing.value) return
        writing.value = true
        scope.launch {
            suspendRunCatching { setPlayed(ids, action == EpisodeSelectionAction.MARK_PLAYED) }
                .onSuccess {
                    val remaining = selected.value - ids.toSet()
                    if (remaining.isEmpty()) anchor = null
                    selected.value = remaining
                }.onFailure(onFailure)
            writing.value = false
        }
    }
}
