// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import ch.lkmc.neutrodyne.core.model.QueueItem
import ch.lkmc.neutrodyne.core.model.VirtualQueue
import ch.lkmc.neutrodyne.playback.api.PlayResult

/** A committed start is projected before the adapter dispatches play and reports PlayResult.Started. */
sealed interface StartOutcome {
    data class Committed(
        val queue: VirtualQueue,
        val startPositionMs: Long,
    ) : StartOutcome

    /** No session mutation or prepare; only an explicit timestamp requests a seek. */
    data class AlreadyCurrent(
        val queue: VirtualQueue,
        val seekPositionMs: Long?,
    ) : StartOutcome

    data class Refused(
        val result: PlayResult,
    ) : StartOutcome
}

/**
 * W3's ordered edit script, applied by W7/desktop on the player thread. Indices refer to the window
 * after preceding ops. SetAll/Clear alone replace current identity; tail edits use indices >= 1.
 * UpdateMetadata may target index 0 with the same ID/source, without prepare or unpin. Metadata
 * comes from PlaybackMediaLookup; adapters build platform items and maintain QueueOrigin side maps.
 */
sealed interface WindowOp {
    data class SetAll(
        val items: List<QueueItem>,
        val startPositionMs: Long,
    ) : WindowOp

    data object Clear : WindowOp

    data class Remove(
        val index: Int,
    ) : WindowOp

    data class Move(
        val fromIndex: Int,
        val toIndex: Int,
    ) : WindowOp

    data class Add(
        val index: Int,
        val item: QueueItem,
    ) : WindowOp

    data class ReplaceTail(
        val items: List<QueueItem>,
    ) : WindowOp

    data class UpdateMetadata(
        val index: Int,
        val item: QueueItem,
    ) : WindowOp

    /** Changed source fingerprint: unpin and remove/add at the same non-current index. */
    data class Reload(
        val index: Int,
        val item: QueueItem,
    ) : WindowOp
}
