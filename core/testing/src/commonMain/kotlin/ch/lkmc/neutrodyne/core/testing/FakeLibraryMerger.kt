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
 * returning [result] — the fixture behind `FirstLinkMerger`'s orchestration tests (the
 * `LINK_*` rules themselves are `:core:data`'s, pinned by its merger tests).
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

    /** Thrown from `mergeStaged` before any callback, for cancellation/failure paths. */
    var failure: (() -> Throwable)? = null

    override suspend fun mergeStaged(
        stagingDir: String,
        policy: MergePolicy,
        onUnmatched: suspend (podcastSyncId: String, identityKey: String) -> Unit,
        progress: (done: Int, total: Int) -> Unit,
        onApplied: (suspend (MergedRecord) -> Unit)?,
    ): Outcome<MergeSummary, BackupError> {
        calls += Call(stagingDir, policy)
        failure?.let { throw it() }
        for ((podcastSyncId, identityKey) in unmatched) onUnmatched(podcastSyncId, identityKey)
        if (progressTotal > 0) progress(progressTotal, progressTotal)
        for (record in appliedRecords) onApplied?.invoke(record)
        return result
    }
}
