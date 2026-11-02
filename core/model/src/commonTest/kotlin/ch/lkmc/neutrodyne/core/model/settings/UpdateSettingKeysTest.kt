// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ch.lkmc.neutrodyne.core.model.settings.UpdateSettingKeys as Keys

class UpdateSettingKeysTest {
    @Test fun registryContainsExactlySixTypedKeysWithDocumentedDefaults() {
        val keys = AllSettingKeys.list.filter { it.name.startsWith("updates.") }
        assertEquals(Keys.ALL, keys)
        assertEquals(listOf(true, -1L, -1L, -1L, false, -1L), keys.map { it.default })
        assertEquals(
            listOf(
                "updates.check_enabled",
                "updates.last_check_at",
                "updates.skipped_version_code",
                "updates.notified_version_code",
                "updates.first_run_choice_done",
                "updates.verification_notice_shown_at",
            ),
            keys.map { it.name },
        )
        assertEquals(listOf(Keys.CHECK_ENABLED), keys.filter { it.file == SettingsFile.PORTABLE })
        assertTrue(keys.none { it.synced })
        assertTrue(keys.all { validateSettingKey(it).isEmpty() })
    }

    @Test fun timestampsAndCodesRejectInvalidStoredValues() {
        for (key in listOf(
            Keys.LAST_CHECK_AT,
            Keys.VERIFICATION_NOTICE_SHOWN_AT,
            Keys.SKIPPED_VERSION_CODE,
            Keys.NOTIFIED_VERSION_CODE,
        )) {
            assertTrue(AllSettingKeys.acceptsValue(key, -1L))
            assertTrue(AllSettingKeys.acceptsValue(key, Long.MAX_VALUE))
            assertFalse(AllSettingKeys.acceptsValue(key, -2L))
            assertFalse(AllSettingKeys.acceptsValue(key, "1"))
            assertFalse(AllSettingKeys.acceptsValue(key, 1))
        }
        assertTrue(AllSettingKeys.acceptsValue(Keys.LAST_CHECK_AT, 0L))
        assertFalse(AllSettingKeys.acceptsValue(Keys.SKIPPED_VERSION_CODE, 0L))
        assertFalse(AllSettingKeys.acceptsValue(Keys.NOTIFIED_VERSION_CODE, 0L))
        assertFalse(AllSettingKeys.acceptsValue(Keys.CHECK_ENABLED, "true"))
    }

    @Test fun absentRepresentationRoundTripsWithoutNullablePreferences() {
        for (value in listOf(null, 0L, 123L, Long.MAX_VALUE)) {
            assertEquals(value, Keys.nullableValue(Keys.storedValue(value)))
        }
    }
}
