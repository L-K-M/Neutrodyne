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
        assertEquals(4, DownloadSettingKeys.ALL.size)
        assertTrue(
            DownloadSettingKeys.ALL.all {
                AllSettingKeys.list.count { registered -> registered.name == it.name } ==
                    1
            },
        )
    }

    @Test fun onlyManualPolicyIsPortableAndUnknownChoicesFallBack() {
        assertEquals(
            listOf(DownloadSettingKeys.MANUAL_METERED),
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
