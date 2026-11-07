// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.api

/** The user's answer to a held batch (10 Mass-change guard step 3). */
enum class HeldResolution { APPLY, KEEP_MINE, DECIDE_LATER }

/** What `SyncController.resolveHeld` did with the batch (10 Mass-change guard step 3). */
sealed interface HeldResolveResult {
    /**
     * The decision went through: Apply's removals and Keep mine's re-capture are committed and the
     * row is deleted, or Decide later deliberately kept the batch.
     */
    data object Resolved : HeldResolveResult

    /** No held row carries the id; another resolution already decided the batch. */
    data object Gone : HeldResolveResult

    /** The decision failed before committing; the batch stays held and can be retried. */
    data object Failed : HeldResolveResult
}

/** One held mass-change batch, mapped to 08's dialog (R7.7). */
data class MassChangePrompt(
    val id: Long,
    /** The device that staged the removals; `null` renders the generic wording. */
    val deviceName: String?,
    val podcastTitles: List<String>,
    val groupNames: List<String>,
)
