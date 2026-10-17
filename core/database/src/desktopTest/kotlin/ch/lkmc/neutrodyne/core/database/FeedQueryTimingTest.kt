// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import ch.lkmc.neutrodyne.core.testing.database.FeedTimingProbe
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/** Desktop sanity checks share the full device fixture; elapsed time never gates this test. */
class FeedQueryTimingTest {
    @Test
    fun recordSeededFeedPageMedians() =
        runTest(timeout = 5.minutes) {
            val dir = Files.createTempDirectory("feed-timing-")
            val db = TestDb.file(dir)
            try {
                println(FeedTimingProbe.record(db))
            } finally {
                db.close()
                dir.toFile().deleteRecursively()
            }
        }
}
