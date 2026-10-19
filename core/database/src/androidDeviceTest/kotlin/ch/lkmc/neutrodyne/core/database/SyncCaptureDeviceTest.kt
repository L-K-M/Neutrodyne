// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.neutrodyne.core.testing.database.SyncBookkeepingProbe
import ch.lkmc.neutrodyne.core.testing.database.SyncCaptureProbe
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The same assertions execute on each shipped/fallback driver on the API26/36 matrix. */
@RunWith(Parameterized::class)
class SyncCaptureDeviceTest(
    private val driver: SQLiteDriver,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun drivers(): List<Array<Any>> = listOf(arrayOf(AndroidSQLiteDriver()), arrayOf(BundledSQLiteDriver()))
    }

    @Test fun requiresWriter() = verify(SyncBookkeepingProbe::requiresWriter)

    @Test fun disabled() = verify(SyncBookkeepingProbe::disabled)

    @Test fun applying() = verify(SyncBookkeepingProbe::applying)

    @Test fun exceptionRollback() = verify(SyncBookkeepingProbe::exceptionRollback)

    @Test fun cancellation() = verify(SyncBookkeepingProbe::cancellation)

    @Test fun historicalAndReplay() = verify(SyncBookkeepingProbe::historicalAndReplay)

    @Test fun ticksAndAcknowledgements() = verify(SyncBookkeepingProbe::ticksAndAcknowledgements)

    @Test fun restamp() = verify(SyncBookkeepingProbe::restamp)

    @Test fun rekeyAndOpaqueClocks() = verify(SyncBookkeepingProbe::rekeyAndOpaqueClocks)

    @Test fun unlink() = verify(SyncBookkeepingProbe::unlink)

    @Test fun everySyncedUpdate() = verify(SyncCaptureProbe::everySyncedUpdate)

    @Test fun localColumnsAndSuppression() = verify(SyncCaptureProbe::localColumnsAndSuppression)

    @Test fun insertsDeletesAndLiterals() = verify(SyncCaptureProbe::insertsDeletesAndLiterals)

    @Test fun cascades() = verify(SyncCaptureProbe::cascades)

    @Test fun rekeys() = verify(SyncCaptureProbe::rekeys)

    @Test fun rollback() = verify(SyncCaptureProbe::rollback)

    @Test fun costs() = verify { println(SyncCaptureProbe.costs(it)) }

    private fun verify(probe: suspend (NeutrodyneDatabase) -> Unit) =
        runTest {
            val db = TestDb.linked(InstrumentationRegistry.getInstrumentation().targetContext, driver = driver)
            try {
                probe(db)
            } finally {
                db.close()
            }
        }
}
