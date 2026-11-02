// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.model

/** source attributes enabled; NotSupported is a missing capability, never an explicit disable.
 * Desktop resolution preserves network/charging attribution; admission ignores both there. */
data class EffectiveAutoDownload(
    val enabled: Boolean,
    val keepLatest: Int,
    val network: NetworkPolicy,
    val requireCharging: Boolean,
    val includeVideo: Boolean,
    val deleteAfterPlayed: DeleteAfter,
    val source: SettingSource,
)

/** Visible group/podcast bulk request, capped at 200 IDs; W8 owns query/estimate behavior. */
data class DownloadAllEstimate(
    val episodeIds: List<Long>,
    val totalCandidates: Int,
    val knownBytes: Long,
    val unknownSizeCount: Int,
    val capped: Boolean,
)
