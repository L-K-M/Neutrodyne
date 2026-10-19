// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import ch.lkmc.neutrodyne.core.testing.database.SyncCaptureProbe
import kotlin.test.Test

class SyncTriggerCostTest {
    @Test fun costs() = verifySync { println(SyncCaptureProbe.costs(it)) }
}
