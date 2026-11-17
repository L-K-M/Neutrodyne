// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsGateTest {
    @Test fun returnsOnceBothSettingsFilesHaveEmitted() =
        runTest {
            val settings = FakeSettingsRepository()
            val gate = async { settings.awaitSettingsFiles() }
            runCurrent()
            assertTrue(gate.isCompleted)
        }

    @Test fun waitsOnAKeyFromEverySettingsFile() =
        assertEquals(SettingsFile.entries.toSet(), SETTINGS_FILE_SENTINELS.map { it.file }.toSet())
}
