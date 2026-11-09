// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The Android host runs the shared [SyncProtectedAuthTestBase] under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncProtectedAuthAndroidHostTest : SyncProtectedAuthTestBase() {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun newTestDb(scheduler: TestCoroutineScheduler): NeutrodyneDatabase =
        TestDb.inMemory(RuntimeEnvironment.getApplication(), queryContext = UnconfinedTestDispatcher(scheduler))
}
