// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import ch.lkmc.neutrodyne.core.model.QueueItem
import dev.zacsweers.metro.Inject

/** Low-churn projection input. Local hints affect tail pins, never the loaded current pin. */
data class WindowEntry(
    val item: QueueItem,
    val media: PlaybackMediaInfo?,
    val localUri: String? = null,
)

/** Pure ordered script; retained identities (especially next) keep their prepared buffers. */
@Inject
class WindowDiff {
    fun diff(
        have: List<WindowEntry>,
        want: List<WindowEntry>,
        currentIndex: Int = 0,
        startPositionMs: Long = 0,
    ): List<WindowOp> {
        require(startPositionMs >= 0)
        require(have.isEmpty() || currentIndex in have.indices)
        require(want.map { it.item.episodeId }.distinct().size == want.size)
        if (want.isEmpty()) return if (have.isEmpty()) emptyList() else listOf(WindowOp.Clear)
        if (have.isEmpty() || have[currentIndex].item.episodeId != want[0].item.episodeId) {
            return listOf(WindowOp.SetAll(want.map { it.item }, startPositionMs))
        }

        val prefix = List(currentIndex) { WindowOp.Remove(0) }
        val before = have.drop(currentIndex)
        val working = before.map { it.item.episodeId }.toMutableList()
        val desired = want.map { it.item.episodeId }
        val tailOps = mutableListOf<WindowOp>()
        val desiredSet = desired.toSet()
        for (index in working.lastIndex downTo 1) {
            if (working[index] !in desiredSet) {
                tailOps += WindowOp.Remove(index)
                working.removeAt(index)
            }
        }

        val anchors = lcs(working.drop(1), desired.drop(1))
        // Work backwards around LCS anchors. Moving only non-anchors gives a single Move for
        // rotating/swapping the next item, rather than rebuilding its source or moving every peer.
        for (index in desired.lastIndex downTo 1) {
            val id = desired[index]
            if (id in anchors) continue
            val from = working.indexOf(id)
            if (from >= 0) working.removeAt(from)
            val to = if (index == desired.lastIndex) working.size else working.indexOf(desired[index + 1])
            working.add(to, id)
            if (from < 0) {
                tailOps += WindowOp.Add(to, want[index].item)
            } else if (from != to) {
                tailOps += WindowOp.Move(from, to)
            }
        }

        val old = before.associateBy { it.item.episodeId }
        for ((index, entry) in want.withIndex()) {
            val prior = old[entry.item.episodeId] ?: continue
            if (index > 0 && !sameSource(prior, entry)) {
                tailOps += WindowOp.Reload(index, entry.item)
            } else if (!sameMetadata(prior.media, entry.media) || prior.item != entry.item) {
                tailOps += WindowOp.UpdateMetadata(index, entry.item)
            }
        }
        if (tailOps.size <= MAX_OPS) return prefix + tailOps

        // Current metadata remains independently editable, even when the tail is replaced.
        return prefix + tailOps.filterIsInstance<WindowOp.UpdateMetadata>().filter { it.index == 0 } +
            WindowOp.ReplaceTail(want.drop(1).map { it.item })
    }

    private fun sameMetadata(
        a: PlaybackMediaInfo?,
        b: PlaybackMediaInfo?,
    ): Boolean {
        if (a == null || b == null) return a == b

        return a.copy(
            sourceType = b.sourceType,
            enclosureUrl = b.enclosureUrl,
            enclosureLength = b.enclosureLength,
            externalMediaId = b.externalMediaId,
        ) == b
    }

    private fun sameSource(
        a: WindowEntry,
        b: WindowEntry,
    ): Boolean =
        a.media?.sourceType == b.media?.sourceType &&
            a.media?.enclosureUrl == b.media?.enclosureUrl &&
            a.media?.enclosureLength == b.media?.enclosureLength &&
            a.media?.externalMediaId == b.media?.externalMediaId && a.localUri == b.localUri

    private fun lcs(
        have: List<Long>,
        want: List<Long>,
    ): Set<Long> {
        val lengths = Array(have.size + 1) { IntArray(want.size + 1) }
        for (i in have.indices.reversed()) {
            for (j in want.indices.reversed()) {
                lengths[i][j] =
                    if (have[i] == want[j]) {
                        lengths[i + 1][j + 1] + 1
                    } else {
                        maxOf(lengths[i + 1][j], lengths[i][j + 1])
                    }
            }
        }
        val kept = mutableSetOf<Long>()
        var i = 0
        var j = 0
        while (i < have.size && j < want.size) {
            when {
                have[i] == want[j] -> {
                    kept += have[i]
                    i++
                    j++
                }

                lengths[i + 1][j] >= lengths[i][j + 1] -> {
                    i++
                }

                else -> {
                    j++
                }
            }
        }
        return kept
    }

    private companion object {
        const val MAX_OPS = 30
    }
}
