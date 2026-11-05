// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.model

import ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadSettingsTest {
    @Test fun keysHaveTypedDefaultsAndAreNeverSynced() {
        assertEquals(ManualMeteredPolicy.ASK, DownloadSettingKeys.MANUAL_METERED.default)
        assertEquals("ext:primary", DownloadSettingKeys.ROOT_ID.default)
        assertFalse(DownloadSettingKeys.NOTIFICATION_PROMPTED.default)
        assertTrue(DownloadSettingKeys.DEFERRED_DELETES.default.isEmpty())
        assertTrue(AllSettingKeys.list.containsAll(DownloadSettingKeys.ALL))
        assertTrue(DownloadSettingKeys.ALL.none { it.synced })
        assertEquals(15, DownloadSettingKeys.ALL.size)
        assertTrue(
            DownloadSettingKeys.ALL.all {
                AllSettingKeys.list.count { registered -> registered.name == it.name } ==
                    1
            },
        )
    }

    @Test fun automaticDefaultsAndRanges() {
        assertFalse(DownloadSettingKeys.AUTO_DOWNLOAD.default)
        assertEquals(3, DownloadSettingKeys.AUTO_DOWNLOAD_KEEP_LATEST.default)
        assertEquals(NetworkPolicy.UNMETERED, DownloadSettingKeys.AUTO_DOWNLOAD_NETWORK.default)
        assertFalse(DownloadSettingKeys.AUTO_DOWNLOAD_REQUIRE_CHARGING.default)
        assertFalse(DownloadSettingKeys.AUTO_DOWNLOAD_INCLUDE_VIDEO.default)
        assertEquals(DeleteAfter.AFTER_24H, DownloadSettingKeys.DELETE_AFTER_PLAYED.default)
        assertEquals(0, DownloadSettingKeys.STORAGE_CAP_MB.default)
        for (keep in 0..11) {
            assertEquals(
                keep in 1..10,
                AllSettingKeys.acceptsValue(DownloadSettingKeys.AUTO_DOWNLOAD_KEEP_LATEST, keep),
            )
        }
        for (cap in listOf(-1, 0, 1_000, 1_024, 2_048, 5_120, 10_240, 20_480)) {
            assertEquals(
                cap in DownloadSettingKeys.STORAGE_CAP_MB_VALUES,
                AllSettingKeys.acceptsValue(DownloadSettingKeys.STORAGE_CAP_MB, cap),
            )
        }
        assertFalse(AllSettingKeys.acceptsValue(DownloadSettingKeys.PLANNER_BASELINE_AT, -1L))
        assertEquals(0L, DownloadSettingKeys.CLEANUP_LAST_AT.default)
        assertEquals("", DownloadSettingKeys.MOVE_TARGET.default)
        assertTrue(DownloadSettingKeys.DESKTOP_ROOTS.default.isEmpty())
    }

    @Test fun portablePoliciesAndUnknownChoicesFallBack() {
        assertEquals(
            listOf(
                DownloadSettingKeys.MANUAL_METERED,
                DownloadSettingKeys.AUTO_DOWNLOAD,
                DownloadSettingKeys.AUTO_DOWNLOAD_KEEP_LATEST,
                DownloadSettingKeys.AUTO_DOWNLOAD_NETWORK,
                DownloadSettingKeys.AUTO_DOWNLOAD_REQUIRE_CHARGING,
                DownloadSettingKeys.AUTO_DOWNLOAD_INCLUDE_VIDEO,
                DownloadSettingKeys.DELETE_AFTER_PLAYED,
                DownloadSettingKeys.STORAGE_CAP_MB,
            ),
            DownloadSettingKeys.ALL.filter {
                it.file ==
                    SettingsFile.PORTABLE
            },
        )
        assertEquals(ManualMeteredPolicy.ASK, DownloadSettingKeys.MANUAL_METERED.fromStored("bad"))
        for (policy in ManualMeteredPolicy.entries) {
            assertTrue(AllSettingKeys.acceptsValue(DownloadSettingKeys.MANUAL_METERED, policy))
            assertEquals(policy, DownloadSettingKeys.MANUAL_METERED.fromStored(policy.name))
        }
        assertFalse(AllSettingKeys.acceptsValue(DownloadSettingKeys.MANUAL_METERED, "ALWAYS"))
        assertFalse(AllSettingKeys.acceptsValue(DownloadSettingKeys.NOTIFICATION_PROMPTED, "false"))
        assertFalse(AllSettingKeys.acceptsValue(DownloadSettingKeys.ROOT_ID, 1))
        assertFalse(AllSettingKeys.acceptsValue(DownloadSettingKeys.DEFERRED_DELETES, setOf(1)))
    }
}
