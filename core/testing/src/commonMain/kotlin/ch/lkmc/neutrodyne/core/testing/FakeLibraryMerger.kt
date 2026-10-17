// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.LibraryMerger
import ch.lkmc.neutrodyne.core.model.MergePolicy
import ch.lkmc.neutrodyne.core.model.MergeSummary
import ch.lkmc.neutrodyne.core.model.MergedRecord

/**
 * Records [LibraryMerger] calls and feeds the configured fixtures to the callbacks before
 * returning [result]. Used by restore-runner tests now and by `FirstLinkMerger` tests in
 * MS2 — the `LINK_*` policies are representable but have no production implementation
 * until then (05 RestoreMerger).
 */
class FakeLibraryMerger : LibraryMerger {
    data class Call(
        val stagingDir: String,
        val policy: MergePolicy,
    )

    val calls = mutableListOf<Call>()
    var result: Outcome<MergeSummary, BackupError> =
        Outcome.Success(MergeSummary(0, 0, 0, 0, 0, 0, 0, 0))

    /** Fed to `onUnmatched` as (podcastSyncId, identityKey) pairs. */
    var unmatched: List<Pair<String, String>> = emptyList()

    /** When positive, `progress` is called once with (progressTotal, progressTotal). */
    var progressTotal: Int = 0

    /** Fed to `onApplied` when the caller passes one. */
    var appliedRecords: List<MergedRecord> = emptyList()

    override suspend fun mergeStaged(
        stagingDir: String,
        policy: MergePolicy,
        onUnmatched: suspend (podcastSyncId: String, identityKey: String) -> Unit,
        progress: (done: Int, total: Int) -> Unit,
        onApplied: (suspend (MergedRecord) -> Unit)?,
    ): Outcome<MergeSummary, BackupError> {
        calls += Call(stagingDir, policy)
        for ((podcastSyncId, identityKey) in unmatched) onUnmatched(podcastSyncId, identityKey)
        if (progressTotal > 0) progress(progressTotal, progressTotal)
        for (record in appliedRecords) onApplied?.invoke(record)
        return result
    }
}
