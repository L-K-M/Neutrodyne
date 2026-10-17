// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.SnapshotScheduler

/** Counts [requestSoon] pokes (05 Snapshot production). */
class FakeSnapshotScheduler : SnapshotScheduler {
    var requestSoonCalls = 0
        private set

    override suspend fun requestSoon() {
        requestSoonCalls++
    }
}
