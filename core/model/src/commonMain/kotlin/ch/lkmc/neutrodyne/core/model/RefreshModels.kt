// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/**
 * One ingest's newly discovered episode set, emitted through `IngestionEvents` (03 Events).
 * [initialFetch] marks the bulk insertions of a subscribe/import/restore ingest — consumers must
 * not react user-visibly to those.
 */
data class NewEpisodes(
    val podcastId: Long,
    val episodeIds: List<Long>,
    val initialFetch: Boolean,
)

/**
 * Whether a podcast-table write came from the user on this device or from applying a pulled sync
 * change (03 Unsubscribe; 10 captures nothing for [SYNC]).
 */
enum class ChangeOrigin { LOCAL, SYNC }
