// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.model.MergePolicy
import ch.lkmc.neutrodyne.core.model.MergeSummary
import ch.lkmc.neutrodyne.core.model.MergedRecord
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The fake feeds its fixtures to the callbacks in order and records the call (05). */
class FakeLibraryMergerTest {
    @Test fun forwardsCallbacksAndReturnsTheFixture() =
        runTest {
            val merger = FakeLibraryMerger()
            val summary = MergeSummary(1, 2, 3, 4, 5, 6, 7, 8)
            merger.result = Outcome.Success(summary)
            merger.unmatched = listOf("sync-1" to "id-1", "sync-2" to "id-2")
            merger.progressTotal = 5
            merger.appliedRecords = listOf(MergedRecord("podcast", "rid-1", emptyMap()))

            val unmatched = mutableListOf<Pair<String, String>>()
            val progress = mutableListOf<Pair<Int, Int>>()
            val applied = mutableListOf<MergedRecord>()
            val result =
                merger.mergeStaged(
                    stagingDir = "/tmp/staging",
                    policy = MergePolicy.RESTORE_MERGE,
                    onUnmatched = { syncId, key -> unmatched += syncId to key },
                    progress = { done, total -> progress += done to total },
                    onApplied = { applied += it },
                )

            assertEquals(listOf(FakeLibraryMerger.Call("/tmp/staging", MergePolicy.RESTORE_MERGE)), merger.calls)
            assertEquals(merger.unmatched, unmatched)
            assertEquals(listOf(5 to 5), progress)
            assertEquals(merger.appliedRecords, applied)
            assertEquals(Outcome.Success(summary), result)
        }

    @Test fun linkPoliciesAreRepresentableAndRecorded() =
        runTest {
            val merger = FakeLibraryMerger()
            merger.result = Outcome.Failure(BackupError.Unsupported)

            val result =
                merger.mergeStaged(
                    stagingDir = "/tmp/staging",
                    policy = MergePolicy.LINK_MERGE,
                    onUnmatched = { _, _ -> },
                    progress = { _, _ -> },
                )

            assertEquals(MergePolicy.LINK_MERGE, merger.calls.single().policy)
            assertEquals(Outcome.Failure(BackupError.Unsupported), result)
        }
}
