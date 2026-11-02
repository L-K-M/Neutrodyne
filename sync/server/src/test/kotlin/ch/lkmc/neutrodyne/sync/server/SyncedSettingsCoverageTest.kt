// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every key declared `synced = true` must be exported with its declared wire type. */
class SyncedSettingsCoverageTest {
    @Test fun exportedSettingsCoverEveryDeclaredSyncedKey() {
        val declared =
            AllSettingKeys.list
                .filter { it.synced }
                .associate { it.name to wireType(it) }
        assertEquals(declared, AccountArchiveWriter.SYNCED_SETTINGS)
    }

    private fun wireType(key: SettingKey<*>): String =
        when (key) {
            is SettingKey.Bool -> "Bool"
            is SettingKey.Int32 -> "Int32"
            is SettingKey.Int64 -> "Int64"
            is SettingKey.Float32 -> "Float32"
            is SettingKey.Text -> "Text"
            is SettingKey.TextSet -> "TextSet"
            is SettingKey.Choice<*> -> "Choice"
        }
}
