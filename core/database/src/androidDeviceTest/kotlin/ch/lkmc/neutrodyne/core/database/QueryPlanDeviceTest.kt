// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.neutrodyne.core.testing.database.FeedQueryProbe
import ch.lkmc.neutrodyne.core.testing.database.SeedDatabase
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The GMD half of `QueryPlanTest` (02 Testing; PLAN M2 AC1/AC10): the same source x filter x
 * order matrix, row exactness, page-boundary determinism and `EXPLAIN QUERY PLAN` assertions on a
 * real device. The bundled-driver run is the authoritative plan check — the device's framework
 * SQLite version may legitimately plan differently, so that run covers rows and seams only.
 * The seed stays small enough for the instrumented CI job.
 */
@RunWith(AndroidJUnit4::class)
class QueryPlanDeviceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun feedRowsPagesAndPlansOnTheBundledDriver() =
        runTest {
            val db = TestDb.inMemory(context, BundledSQLiteDriver())
            try {
                FeedQueryProbe.assertAll(db, DEVICE_SEED.install(db))
            } finally {
                db.close()
            }
        }

    @Test
    fun feedRowsAndPagesOnTheFrameworkDriver() =
        runTest {
            val db = TestDb.inMemory(context, AndroidSQLiteDriver())
            try {
                val seeded = DEVICE_SEED.install(db)
                FeedQueryProbe.assertRowsMatchReference(db, seeded)
                FeedQueryProbe.assertPagesDeterministicAcrossBoundaries(db, seeded)
            } finally {
                db.close()
            }
        }

    private companion object {
        /** ~1,200 episodes: full matrix coverage at a fraction of the desktop seed's cost. */
        val DEVICE_SEED =
            SeedDatabase(
                podcasts = 36,
                episodes = 1_200,
                groups = 5,
                downloads = 60,
                inProgress = 25,
                favourites = 40,
                youtubeChannels = 6,
            )
    }
}
