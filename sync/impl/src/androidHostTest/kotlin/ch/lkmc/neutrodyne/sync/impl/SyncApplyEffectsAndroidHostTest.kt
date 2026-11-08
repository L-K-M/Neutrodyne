// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The Android host runs the shared [SyncApplyEffectsTestBase] under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncApplyEffectsAndroidHostTest : SyncApplyEffectsTestBase() {
    override fun newTestDb(): NeutrodyneDatabase = TestDb.inMemory(RuntimeEnvironment.getApplication())
}
