// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import ch.lkmc.neutrodyne.core.testing.database.FeedInvalidationProbe
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class InvalidationHygieneTest {
    @Test
    fun positionOnlyWritesDoNotInvalidateOpenFeeds() =
        runTest {
            val db = TestDb.inMemory(queryContext = StandardTestDispatcher(testScheduler))
            try {
                FeedInvalidationProbe.positionWrites(db, this)
            } finally {
                db.close()
            }
        }

    @Test
    fun theFirstStartedStateTransitionInvalidatesOnlyOnce() =
        runTest {
            val db = TestDb.inMemory(queryContext = StandardTestDispatcher(testScheduler))
            try {
                FeedInvalidationProbe.firstPositionStartsStateOnce(db, this)
            } finally {
                db.close()
            }
        }
}
