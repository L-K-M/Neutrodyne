// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.feeds.backup.SettingValueV1
import ch.lkmc.neutrodyne.feeds.backup.SettingsV1
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import ch.lkmc.neutrodyne.core.model.settings.UpdateSettingKeys as Keys

class UpdateRestoreSettingsTest {
    @Test fun restoreRejectsWrongTypesAndAllDeviceUpdateKeys() =
        runTest {
            val settings = FakeSettingsRepository()
            val entries =
                listOf(
                    SettingValueV1(Keys.CHECK_ENABLED.name, "Bool", JsonPrimitive(false)),
                    SettingValueV1(Keys.CHECK_ENABLED.name, "Bool", JsonPrimitive("true")),
                    SettingValueV1(Keys.CHECK_ENABLED.name, "Int64", JsonPrimitive(1L)),
                ) + Keys.ALL.drop(1).map { SettingValueV1(it.name, "Int64", JsonPrimitive(123L)) }
            val counts =
                assertIs<Outcome.Success<SettingsMergeCounts>>(
                    RestoreSettingsMerge(settings).apply(SettingsV1(entries)),
                ).value
            assertEquals(1, counts.applied)
            assertEquals(7, counts.skipped)
            assertEquals(false, settings.get(Keys.CHECK_ENABLED))
            assertEquals(-1L, settings.get(Keys.NOTIFIED_VERSION_CODE))
        }
}
