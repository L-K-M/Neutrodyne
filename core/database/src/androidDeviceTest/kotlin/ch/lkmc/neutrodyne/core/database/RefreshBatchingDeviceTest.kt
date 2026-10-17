// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.neutrodyne.core.testing.database.FeedInvalidationProbe
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RefreshBatchingDeviceTest {
    @Test
    fun threeHundred304OutcomesInvalidateAtMostFifteenTimes() =
        runTest {
            val db =
                TestDb.inMemory(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    BundledSQLiteDriver(),
                    queryContext = StandardTestDispatcher(testScheduler),
                )
            try {
                FeedInvalidationProbe.refreshBatching(db, this)
            } finally {
                db.close()
            }
        }
}
