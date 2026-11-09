// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

class SyncEngineEffectsDrainTest : SyncEngineEffectsDrainTestBase() {
    override fun newTestDb() =
        ch.lkmc.neutrodyne.core.testing.database.TestDb
            .inMemory()
}
