// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import ch.lkmc.neutrodyne.core.testing.database.FeedInvalidationProbe
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class RefreshBatchingTest {
    @Test
    fun threeHundred304OutcomesInvalidateAtMostFifteenTimes() =
        runTest {
            val db = TestDb.inMemory(queryContext = StandardTestDispatcher(testScheduler))
            try {
                FeedInvalidationProbe.refreshBatching(db, this)
            } finally {
                db.close()
            }
        }
}
