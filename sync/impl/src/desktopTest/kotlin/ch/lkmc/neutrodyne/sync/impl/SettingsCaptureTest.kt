// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher

class SettingsCaptureTest : SettingsCaptureTestBase() {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun newTestDb(scheduler: TestCoroutineScheduler): NeutrodyneDatabase =
        TestDb.inMemory(queryContext = UnconfinedTestDispatcher(scheduler))
}
