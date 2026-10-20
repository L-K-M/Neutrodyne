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
import ch.lkmc.neutrodyne.core.model.settings.DiscoverSettingKeys as Keys

class M7RestoreSettingsTest {
    @Test fun restoreCarriesChoicesAndRejectsInvalidCountryDeviceStateAndSecrets() =
        runTest {
            val settings = FakeSettingsRepository()
            val values =
                listOf(
                    SettingValueV1(Keys.COUNTRY.name, "Text", JsonPrimitive("CH")),
                    SettingValueV1(Keys.APPLE_ENABLED.name, "Bool", JsonPrimitive(false)),
                    SettingValueV1(Keys.FYYD_ENABLED.name, "Bool", JsonPrimitive(false)),
                    SettingValueV1(Keys.PODCASTINDEX_ENABLED.name, "Bool", JsonPrimitive(false)),
                    SettingValueV1(Keys.COUNTRY.name, "Text", JsonPrimitive("ch")),
                    SettingValueV1(Keys.COUNTRY.name, "Text", JsonPrimitive("USA")),
                    SettingValueV1(Keys.COUNTRY.name, "Text", JsonPrimitive(12)),
                    SettingValueV1(Keys.APPLE_ENABLED.name, "Bool", JsonPrimitive("true")),
                    SettingValueV1(Keys.SUGGESTED_GROUPS_DISMISSED.name, "Bool", JsonPrimitive(true)),
                    SettingValueV1("discover.podcastindex_key", "Text", JsonPrimitive("excluded")),
                    SettingValueV1("discover.podcastindex_secret", "Text", JsonPrimitive("excluded")),
                )
            val counts =
                assertIs<Outcome.Success<SettingsMergeCounts>>(
                    RestoreSettingsMerge(settings).apply(SettingsV1(values)),
                ).value
            assertEquals(4, counts.applied)
            assertEquals(7, counts.skipped)
            assertEquals("CH", settings.get(Keys.COUNTRY))
            assertEquals(false, settings.get(Keys.APPLE_ENABLED))
            assertEquals(false, settings.get(Keys.FYYD_ENABLED))
            assertEquals(false, settings.get(Keys.PODCASTINDEX_ENABLED))
            assertEquals(false, settings.get(Keys.SUGGESTED_GROUPS_DISMISSED))
        }
}
