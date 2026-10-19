// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import ch.lkmc.neutrodyne.core.testing.database.SyncCaptureProbe
import kotlin.test.Test

class SyncCaptureTest {
    @Test fun everySyncedUpdate() = verifySync(SyncCaptureProbe::everySyncedUpdate)

    @Test fun localColumnsAndSuppression() = verifySync(SyncCaptureProbe::localColumnsAndSuppression)

    @Test fun insertsDeletesAndLiterals() = verifySync(SyncCaptureProbe::insertsDeletesAndLiterals)

    @Test fun cascades() = verifySync(SyncCaptureProbe::cascades)

    @Test fun rekeys() = verifySync(SyncCaptureProbe::rekeys)

    @Test fun rollback() = verifySync(SyncCaptureProbe::rollback)
}
