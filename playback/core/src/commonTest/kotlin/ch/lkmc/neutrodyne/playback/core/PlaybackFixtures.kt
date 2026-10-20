// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import ch.lkmc.neutrodyne.core.model.QueueItem
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.VirtualQueue

internal fun media(
    id: Long,
    source: SourceType = SourceType.RSS,
) = PlaybackMediaInfo(
    episodeId = id,
    podcastId = 1,
    title = "Episode $id",
    podcastTitle = "Podcast",
    artwork = ArtworkRef("art", null, 0),
    sourceType = source,
    availability = Availability.AVAILABLE,
    isVideo = false,
    enclosureUrl = if (source == SourceType.RSS) "https://example.org/$id.mp3" else null,
    enclosureLength = null,
    externalMediaId = if (source == SourceType.RSS) null else "video$id",
    durationMs = 1_200_000,
    measuredDurationMs = null,
    playedAt = null,
    sortDate = 1000 + id,
)

internal fun item(
    id: Long,
    origin: QueueOrigin = QueueOrigin.UP_NEXT,
) = QueueItem(id, origin)

internal fun entry(id: Long) = WindowEntry(item(id), media(id))

internal fun queue(
    generation: Long = 1,
    current: Long? = 1,
    upNext: List<Long> = listOf(2, 3),
    context: List<Long> = listOf(4, 5),
) = VirtualQueue(
    generation,
    current?.let {
        item(it, QueueOrigin.CURRENT)
    },
    upNext.map { item(it) },
    context.map { item(it, QueueOrigin.CONTEXT) },
)

/** Execute W1 ops using their sequential indices, as the platform adapters must. */
internal fun apply(
    have: List<QueueItem>,
    ops: List<WindowOp>,
): List<QueueItem> {
    val result = have.toMutableList()
    for (op in ops) {
        when (op) {
            is WindowOp.SetAll -> {
                result.clear()
                result.addAll(op.items)
            }

            WindowOp.Clear -> {
                result.clear()
            }

            is WindowOp.Remove -> {
                result.removeAt(op.index)
            }

            is WindowOp.Add -> {
                result.add(op.index, op.item)
            }

            is WindowOp.Move -> {
                result.add(op.toIndex, result.removeAt(op.fromIndex))
            }

            is WindowOp.ReplaceTail -> {
                while (result.size >
                    1
                ) {
                    result.removeAt(result.lastIndex)
                }
                result.addAll(op.items)
            }

            is WindowOp.UpdateMetadata -> {
                result[op.index] = op.item
            }

            is WindowOp.Reload -> {
                result[op.index] = op.item
            }
        }
    }
    return result
}
