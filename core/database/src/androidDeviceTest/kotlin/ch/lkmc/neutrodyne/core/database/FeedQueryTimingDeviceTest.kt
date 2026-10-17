// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.neutrodyne.core.testing.database.FeedTimingProbe
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes

/** GMD records PB6-PB8; the reference-device budgets must never gate an emulator build. */
@RunWith(AndroidJUnit4::class)
class FeedQueryTimingDeviceTest {
    @Test
    fun recordSeededFeedPageMedians() =
        runTest(timeout = 5.minutes) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val dir = Files.createTempDirectory(context.cacheDir.toPath(), "feed-timing-").toFile()
            val db = TestDb.file(context, dir, BundledSQLiteDriver())
            try {
                val result = FeedTimingProbe.record(db)
                val report =
                    "FeedQueryTimingTest sdk=${Build.VERSION.SDK_INT} device=${Build.MODEL}" +
                        " fingerprint=${Build.FINGERPRINT}\n$result\n"
                // ci.yml collects androidTest-results and androidTests reports. Device-private
                // files are not pulled, so emit both stdout and the instrumentation stream.
                println(report)
                instrumentation.sendStatus(
                    STATUS_IN_PROGRESS,
                    Bundle().apply { putString(Instrumentation.REPORT_KEY_STREAMRESULT, report) },
                )
            } finally {
                db.close()
                dir.deleteRecursively()
            }
        }

    private companion object {
        // AndroidJUnitRunner's stream-only progress status, outside its test start/end events.
        const val STATUS_IN_PROGRESS = 2
    }
}
