// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import ch.lkmc.neutrodyne.core.testing.database.SyncBookkeepingProbe
import kotlin.test.Test

class SyncBookkeepingTest {
    @Test fun requiresWriter() = verifySync(SyncBookkeepingProbe::requiresWriter)

    @Test fun disabled() = verifySync(SyncBookkeepingProbe::disabled)

    @Test fun applying() = verifySync(SyncBookkeepingProbe::applying)

    @Test fun exceptionRollback() = verifySync(SyncBookkeepingProbe::exceptionRollback)

    @Test fun cancellation() = verifySync(SyncBookkeepingProbe::cancellation)

    @Test fun historicalAndReplay() = verifySync(SyncBookkeepingProbe::historicalAndReplay)

    @Test fun ticksAndAcknowledgements() = verifySync(SyncBookkeepingProbe::ticksAndAcknowledgements)

    @Test fun restamp() = verifySync(SyncBookkeepingProbe::restamp)

    @Test fun rekeyAndOpaqueClocks() = verifySync(SyncBookkeepingProbe::rekeyAndOpaqueClocks)

    @Test fun unlink() = verifySync(SyncBookkeepingProbe::unlink)
}
