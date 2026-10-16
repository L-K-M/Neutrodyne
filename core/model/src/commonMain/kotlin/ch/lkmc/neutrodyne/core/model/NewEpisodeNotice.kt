// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/** A run's episodes for one channel; null group identity targets the default channel. */
data class NewEpisodeNotice(
    val groupUuid: String?,
    val groupName: String?,
    val episodes: List<NewEpisodeLine>,
)

/** Lines are ordered newest first by sortDate, then episode ID, both descending. */
data class NewEpisodeLine(
    val episodeId: Long,
    val podcastId: Long,
    val podcastTitle: String,
    val episodeTitle: String,
    val artworkKey: String?,
)
