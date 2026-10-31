// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.settings.HardwareButtons
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.feeds.backup.SettingValueV1
import ch.lkmc.neutrodyne.feeds.backup.SettingsV1
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys as Keys

class M5RestoreSettingsTest {
    @Test fun restoreAcceptsNewKeysAndSkipsBadChoicesRangesAndTypes() =
        runTest {
            val store = FakeSettingsRepository()
            val values =
                listOf(
                    SettingValueV1(Keys.HARDWARE_BUTTONS.name, "Choice", JsonPrimitive("SKIP")),
                    SettingValueV1(Keys.SMART_RESUME.name, "Bool", JsonPrimitive(false)),
                    SettingValueV1(Keys.SLEEP_LAST_MINUTES.name, "Int32", JsonPrimitive(240)),
                    SettingValueV1(Keys.HARDWARE_BUTTONS.name, "Choice", JsonPrimitive("INVALID")),
                    SettingValueV1(Keys.SMART_RESUME.name, "Bool", JsonPrimitive("true")),
                    SettingValueV1(Keys.SLEEP_LAST_MINUTES.name, "Int32", JsonPrimitive(0)),
                    SettingValueV1(Keys.SLEEP_LAST_MINUTES.name, "Int32", JsonPrimitive(241)),
                    SettingValueV1(Keys.SLEEP_LAST_MINUTES.name, "Int32", JsonPrimitive(2147483648L)),
                    SettingValueV1(Keys.SLEEP_LAST_MINUTES.name, "Int32", JsonPrimitive("30")),
                )
            val result =
                assertIs<Outcome.Success<SettingsMergeCounts>>(
                    RestoreSettingsMerge(store).apply(SettingsV1(values)),
                ).value
            assertEquals(3, result.applied)
            assertEquals(6, result.skipped)
            assertEquals(HardwareButtons.SKIP, store.get(Keys.HARDWARE_BUTTONS))
            assertEquals(false, store.get(Keys.SMART_RESUME))
            assertEquals(240, store.get(Keys.SLEEP_LAST_MINUTES))
        }
}
