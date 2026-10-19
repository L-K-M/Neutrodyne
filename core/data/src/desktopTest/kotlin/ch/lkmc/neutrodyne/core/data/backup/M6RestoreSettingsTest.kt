// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data.backup

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.ManualMeteredPolicy
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.feeds.backup.SettingValueV1
import ch.lkmc.neutrodyne.feeds.backup.SettingsV1
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys as Keys

class M6RestoreSettingsTest {
    @Test fun restoreValidatesPolicyAndRejectsDeviceKeys() =
        runTest {
            val settings = FakeSettingsRepository()
            val result =
                assertIs<Outcome.Success<SettingsMergeCounts>>(
                    RestoreSettingsMerge(settings).apply(
                        SettingsV1(
                            listOf(
                                SettingValueV1(Keys.MANUAL_METERED.name, "Choice", JsonPrimitive("ALWAYS")),
                                SettingValueV1(Keys.MANUAL_METERED.name, "Choice", JsonPrimitive("INVALID")),
                                SettingValueV1(Keys.MANUAL_METERED.name, "Bool", JsonPrimitive(true)),
                                SettingValueV1(Keys.ROOT_ID.name, "Text", JsonPrimitive("int")),
                                SettingValueV1(Keys.NOTIFICATION_PROMPTED.name, "Bool", JsonPrimitive(true)),
                                SettingValueV1(Keys.DEFERRED_DELETES.name, "TextSet", null),
                            ),
                        ),
                    ),
                ).value
            assertEquals(1, result.applied)
            assertEquals(5, result.skipped)
            assertEquals(ManualMeteredPolicy.ALWAYS, settings.get(Keys.MANUAL_METERED))
            assertEquals("ext:primary", settings.get(Keys.ROOT_ID))
            assertFalse(settings.get(Keys.NOTIFICATION_PROMPTED))
            assertTrue(settings.get(Keys.DEFERRED_DELETES).isEmpty())
        }

    @Test fun automaticPolicyBackupValidation() =
        runTest {
            val settings = FakeSettingsRepository()
            val entries =
                listOf(
                    SettingValueV1(Keys.AUTO_DOWNLOAD.name, "Bool", JsonPrimitive(true)),
                    SettingValueV1(Keys.AUTO_DOWNLOAD_KEEP_LATEST.name, "Int32", JsonPrimitive(7)),
                    SettingValueV1(Keys.AUTO_DOWNLOAD_KEEP_LATEST.name, "Int32", JsonPrimitive(11)),
                    SettingValueV1(Keys.AUTO_DOWNLOAD_NETWORK.name, "Choice", JsonPrimitive("ANY")),
                    SettingValueV1(Keys.AUTO_DOWNLOAD_NETWORK.name, "Choice", JsonPrimitive("INVALID")),
                    SettingValueV1(Keys.STORAGE_CAP_MB.name, "Int32", JsonPrimitive(1024)),
                    SettingValueV1(Keys.STORAGE_CAP_MB.name, "Int32", JsonPrimitive(1000)),
                    SettingValueV1(Keys.PLANNER_BASELINE_AT.name, "Int64", JsonPrimitive(42L)),
                    SettingValueV1(Keys.MOVE_TARGET.name, "Text", JsonPrimitive("new-root")),
                    SettingValueV1(Keys.CLEANUP_LAST_AT.name, "Int64", JsonPrimitive(42L)),
                )
            val result =
                assertIs<Outcome.Success<SettingsMergeCounts>>(
                    RestoreSettingsMerge(settings).apply(SettingsV1(entries)),
                ).value
            assertEquals(4, result.applied)
            assertEquals(6, result.skipped)
            assertTrue(settings.get(Keys.AUTO_DOWNLOAD))
            assertEquals(7, settings.get(Keys.AUTO_DOWNLOAD_KEEP_LATEST))
            assertEquals(0L, settings.get(Keys.PLANNER_BASELINE_AT))
        }

    @Test fun portableSnapshotExportsPoliciesButNeverDeviceState() =
        runTest {
            val settings = FakeSettingsRepository()
            settings.set(Keys.MANUAL_METERED, ManualMeteredPolicy.NEVER)
            settings.set(Keys.ROOT_ID, "int")
            settings.set(Keys.NOTIFICATION_PROMPTED, true)
            val exported = settings.observePortableSnapshot().first().filterKeys { it.startsWith("downloads.") }
            assertEquals(8, exported.size)
            assertEquals(ManualMeteredPolicy.NEVER, exported[Keys.MANUAL_METERED.name])
            assertEquals(false, exported[Keys.AUTO_DOWNLOAD.name])
            assertFalse(Keys.PLANNER_BASELINE_AT.name in exported)
            assertFalse(Keys.MOVE_TARGET.name in exported)
        }
}
