// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.api

/** The user's answer to a held batch (10 Mass-change guard step 3). */
enum class HeldResolution { APPLY, KEEP_MINE, DECIDE_LATER }

/** One held mass-change batch, mapped to 08's dialog (R7.7). */
data class MassChangePrompt(
    val id: Long,
    /** The device that staged the removals; `null` renders the generic wording. */
    val deviceName: String?,
    val podcastTitles: List<String>,
    val groupNames: List<String>,
)
