// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.MergePolicy
import ch.lkmc.neutrodyne.core.model.MergeSummary
import ch.lkmc.neutrodyne.core.model.MergedRecord

/**
 * The port through which 10's `FirstLinkMerger` reaches `:core:data`'s `RestoreMerger`
 * (05 RestoreMerger): `:sync:impl` may not depend on `:core:data`, so the contract lives
 * here. M3 implements the `RESTORE_*` policies; `LINK_*` stay representable and land in
 * MS2 ([MergePolicy]'s documentation).
 */
interface LibraryMerger {
    /**
     * Merges a staging directory written under the archive's entry names (05). [onUnmatched]
     * receives records for lines a LINK policy declines to stub; [progress] reports
     * `(done, total)`; [onApplied] reports merged records inside their projection
     * transaction and is used only by the link policies (05 First-link metadata).
     */
    suspend fun mergeStaged(
        stagingDir: String,
        policy: MergePolicy,
        onUnmatched: suspend (podcastSyncId: String, identityKey: String) -> Unit,
        progress: (done: Int, total: Int) -> Unit,
        onApplied: (suspend (MergedRecord) -> Unit)? = null,
    ): Outcome<MergeSummary, BackupError>
}
