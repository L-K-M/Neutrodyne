// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

/**
 * A queue diff the controller derives from `QueueWindowPlanner`'s window diff (11
 * DesktopPlaybackController). It never carries the current item: the engine keeps
 * preparing the item that is already open, so a metadata-only update cannot
 * restart audio, and a tail edit cannot replace the item the user hears.
 *
 * The four operations apply in a fixed order — replace, remove, insert, move —
 * which keeps the result independent of how the planner split its own diff.
 */
data class EngineWindowDiff(
    val replaced: List<EngineItem> = emptyList(),
    val removed: Set<Long> = emptySet(),
    val inserted: List<PlacedItem> = emptyList(),
    val moved: List<MovedItem> = emptyList(),
) {
    /**
     * Apply the diff to [window] and return the new window. Throws
     * [IllegalArgumentException] when the diff touches the current item or
     * addresses an index outside the window, so a planner bug fails loudly
     * instead of silently reordering the queue.
     */
    fun applyTo(
        window: List<EngineItem>,
        currentEpisodeId: Long?,
    ): List<EngineItem> {
        require(currentEpisodeId !in removed) {
            "the diff removes the current item $currentEpisodeId"
        }
        for (item in replaced) {
            require(item.episodeId != currentEpisodeId) {
                "the diff replaces the current item ${item.episodeId}"
            }
        }
        var result = window.toMutableList()
        for (item in replaced) {
            val index = result.indexOfFirst { it.episodeId == item.episodeId }
            require(index >= 0) { "the diff replaces unknown item ${item.episodeId}" }
            result[index] = item
        }
        for (episodeId in removed) {
            result.removeAll { it.episodeId == episodeId }
        }
        for (placed in inserted.sortedBy { it.index }) {
            require(placed.index in 0..result.size) {
                "the diff inserts at ${placed.index}, outside 0..${result.size}"
            }
            result.add(placed.index, placed.item)
        }
        for (move in moved) {
            require(move.episodeId != currentEpisodeId) {
                "the diff moves the current item ${move.episodeId}"
            }
            val index = result.indexOfFirst { it.episodeId == move.episodeId }
            require(index >= 0) { "the diff moves unknown item ${move.episodeId}" }
            require(move.toIndex in 0 until result.size) {
                "the diff moves to ${move.toIndex}, outside 0 until ${result.size}"
            }
            result.add(move.toIndex, result.removeAt(index))
        }
        return result
    }
}

/** An insertion point in the window (11 DesktopPlaybackController). */
data class PlacedItem(
    val index: Int,
    val item: EngineItem,
)

/** A move of a non-current item to a new window index. */
data class MovedItem(
    val episodeId: Long,
    val toIndex: Int,
)
