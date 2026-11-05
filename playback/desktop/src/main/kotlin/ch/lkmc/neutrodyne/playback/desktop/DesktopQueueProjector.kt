// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.desktop

import ch.lkmc.neutrodyne.core.model.VirtualQueue
import ch.lkmc.neutrodyne.playback.core.WindowEntry
import ch.lkmc.neutrodyne.playback.core.WindowOp
import ch.lkmc.neutrodyne.playback.engine.AudioEngine
import ch.lkmc.neutrodyne.playback.engine.EngineItem
import ch.lkmc.neutrodyne.playback.engine.EngineWindowDiff
import ch.lkmc.neutrodyne.playback.engine.MovedItem
import ch.lkmc.neutrodyne.playback.engine.PlacedItem

/**
 * Player-dispatcher confined translation; all selection/diff rules stay in
 * playback:core. The engine keeps the outgoing item in its window until the
 * controller's diff removes it, so [currentEpisodeId] is the engine's real
 * current: after an automatic transition it is the arrival, not `items.first()`.
 */
internal class DesktopQueueProjector(
    private val engine: AudioEngine,
    private val resolver: DesktopEpisodeSourceResolver,
) {
    var entries: List<WindowEntry> = emptyList()
        private set

    private var items: List<EngineItem> = emptyList()
    var generation: Long = 0
        private set

    fun apply(
        queue: VirtualQueue,
        desired: List<WindowEntry>,
        operations: List<WindowOp>,
        currentEpisodeId: Long?,
    ) {
        val replacement = operations.filterIsInstance<WindowOp.SetAll>().singleOrNull()
        if (replacement != null || operations.any { it == WindowOp.Clear }) {
            items = desired.map(resolver::project)
            engine.setWindow(items, 0, replacement?.startPositionMs)
        } else {
            // Retain the engine current's source even if a feed refresh changes its
            // URL or a download finishes; every other entry is re-projected.
            val existing = items.associateBy { it.episodeId }
            val next =
                desired.map { entry ->
                    if (entry.item.episodeId == currentEpisodeId) {
                        existing[entry.item.episodeId] ?: resolver.project(entry)
                    } else {
                        resolver.project(entry)
                    }
                }
            val current = currentEpisodeId
            val wanted = next.map { it.episodeId }.toSet()
            val removed = items.filter { it.episodeId !in wanted }.map { it.episodeId }.toSet()
            val working = items.filter { it.episodeId !in removed }.toMutableList()
            val inserted =
                next.mapIndexedNotNull { index, item ->
                    if (working.any { it.episodeId == item.episodeId }) {
                        null
                    } else {
                        PlacedItem(index, item).also { working.add(index, item) }
                    }
                }
            val moved = mutableListOf<MovedItem>()
            next.forEachIndexed { index, item ->
                val from = working.indexOfFirst { it.episodeId == item.episodeId }
                if (from != index) {
                    check(item.episodeId != current)
                    moved += MovedItem(item.episodeId, index)
                    working.add(index, working.removeAt(from))
                }
            }
            val replaced =
                next.filter { item ->
                    item.episodeId != current && items.any { it.episodeId == item.episodeId && it != item }
                }
            val diff = EngineWindowDiff(replaced, removed, inserted, moved)
            if (diff != EngineWindowDiff()) engine.updateWindow(diff)
            items = next
        }
        resolver.retain(items)
        entries = desired
        generation = queue.generation
    }
}
