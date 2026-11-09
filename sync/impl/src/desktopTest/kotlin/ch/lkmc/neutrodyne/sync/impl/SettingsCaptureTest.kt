// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.testing.database.TestDb

class SettingsCaptureTest : SettingsCaptureTestBase() {
    override fun newTestDb(): NeutrodyneDatabase = TestDb.inMemory()
}
