// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.sqlite.driver.AndroidSQLiteDriver
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
class InvalidationHygieneDeviceTest {
    @Test
    fun positionOnlyWritesDoNotInvalidateOpenFeeds() =
        runTest {
            val db =
                TestDb.inMemory(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    BundledSQLiteDriver(),
                    queryContext = StandardTestDispatcher(testScheduler),
                )
            try {
                FeedInvalidationProbe.positionWrites(db, this)
            } finally {
                db.close()
            }
        }

    @Test
    fun theFirstStartedStateTransitionInvalidatesOnlyOnce() =
        runTest {
            val db =
                TestDb.inMemory(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    BundledSQLiteDriver(),
                    queryContext = StandardTestDispatcher(testScheduler),
                )
            try {
                FeedInvalidationProbe.firstPositionStartsStateOnce(db, this)
            } finally {
                db.close()
            }
        }

    @Test
    fun linkedPositionWritesFramework() =
        runTest {
            val db =
                TestDb.linked(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    AndroidSQLiteDriver(),
                    queryContext = StandardTestDispatcher(testScheduler),
                )
            try {
                FeedInvalidationProbe.positionWrites(db, this)
            } finally {
                db.close()
            }
        }

    @Test
    fun linkedFirstPositionStartsStateOnceFramework() =
        runTest {
            val db =
                TestDb.linked(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    AndroidSQLiteDriver(),
                    queryContext = StandardTestDispatcher(testScheduler),
                )
            try {
                FeedInvalidationProbe.firstPositionStartsStateOnce(db, this)
            } finally {
                db.close()
            }
        }

    @Test
    fun linkedPositionWritesBundled() =
        runTest {
            val db =
                TestDb.linked(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    BundledSQLiteDriver(),
                    queryContext = StandardTestDispatcher(testScheduler),
                )
            try {
                FeedInvalidationProbe.positionWrites(db, this)
            } finally {
                db.close()
            }
        }

    @Test
    fun linkedFirstPositionStartsStateOnceBundled() =
        runTest {
            val db =
                TestDb.linked(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    BundledSQLiteDriver(),
                    queryContext = StandardTestDispatcher(testScheduler),
                )
            try {
                FeedInvalidationProbe.firstPositionStartsStateOnce(db, this)
            } finally {
                db.close()
            }
        }
}
