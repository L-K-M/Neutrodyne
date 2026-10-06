// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Registry rules from 01 DataStore files and typed setting keys. `AllSettingKeys.list` is empty in
 * M0a, so the tests exercise [validateSettingKey] directly plus the empty-list invariants — the
 * same assertions keep working as areas land their `ALL` lists.
 */
class SettingKeyTest {
    @Test
    fun `registry entries satisfy every rule`() {
        for (key in AllSettingKeys.list) {
            assertTrue(
                validateSettingKey(key).isEmpty(),
                "'${key.name}': ${validateSettingKey(key)}",
            )
        }
    }

    @Test
    fun `registry names are unique`() {
        val names = AllSettingKeys.list.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `key names must match the area prefix pattern`() {
        val good = SettingKey.Bool("appearance.dark_mode", true, SettingsFile.PORTABLE)
        assertEquals(emptyList(), validateSettingKey(good))

        val badArea = SettingKey.Bool("bogus.dark_mode", true, SettingsFile.PORTABLE)
        val badChars = SettingKey.Bool("appearance.Dark-Mode", true, SettingsFile.PORTABLE)
        val bare = SettingKey.Bool("appearance", true, SettingsFile.PORTABLE)
        for (key in listOf(badArea, badChars, bare)) {
            assertTrue(validateSettingKey(key).isNotEmpty(), "'${key.name}' should be rejected")
        }
    }

    @Test
    fun `ui and desktop keys are device-local`() {
        val ui = SettingKey.Bool("ui.pane_collapsed", false, SettingsFile.DEVICE)
        val desktop = SettingKey.Bool("desktop.minimize_to_tray", false, SettingsFile.DEVICE)
        assertEquals(emptyList(), validateSettingKey(ui))
        assertEquals(emptyList(), validateSettingKey(desktop))

        val misplaced = SettingKey.Bool("ui.pane_collapsed", false, SettingsFile.PORTABLE)
        assertTrue(validateSettingKey(misplaced).isNotEmpty())
    }

    @Test
    fun `synced is only allowed on portable keys outside no-sync prefixes`() {
        val ok = SettingKey.Bool("feeds.refresh_on_start", true, SettingsFile.PORTABLE, synced = true)
        assertEquals(emptyList(), validateSettingKey(ok))

        val deviceSynced = SettingKey.Bool("appearance.theme_id", true, SettingsFile.DEVICE, synced = true)
        val uiSynced = SettingKey.Bool("ui.volume", true, SettingsFile.DEVICE, synced = true)
        val updatesSynced = SettingKey.Bool("updates.auto", true, SettingsFile.PORTABLE, synced = true)
        val syncSynced = SettingKey.Bool("sync.enabled", true, SettingsFile.PORTABLE, synced = true)
        for (key in listOf(deviceSynced, uiSynced, updatesSynced, syncSynced)) {
            assertTrue(validateSettingKey(key).isNotEmpty(), "'${key.name}' should be rejected")
        }
    }

    @Test
    fun `sync server_url must be portable and unsynced`() {
        val good = SettingKey.Text("sync.server_url", "", SettingsFile.PORTABLE)
        assertEquals(emptyList(), validateSettingKey(good))

        val synced = SettingKey.Text("sync.server_url", "", SettingsFile.PORTABLE, synced = true)
        val device = SettingKey.Text("sync.server_url", "", SettingsFile.DEVICE)
        assertTrue(validateSettingKey(synced).isNotEmpty())
        assertTrue(validateSettingKey(device).isNotEmpty())

        // any registered copy obeys the rule too
        AllSettingKeys.list.filter { it.name == "sync.server_url" }.forEach { key ->
            assertEquals(SettingsFile.PORTABLE, key.file)
            assertTrue(!key.synced)
        }
    }

    private enum class Theme { LIGHT, DARK, SYSTEM }

    @Test
    fun `choice falls back to its default for unknown stored names`() {
        val key =
            SettingKey.Choice(
                "appearance.theme",
                Theme.SYSTEM,
                persistentListOf(Theme.LIGHT, Theme.DARK, Theme.SYSTEM),
                synced = true,
            )

        assertEquals(Theme.DARK, key.fromStored("DARK"))
        assertEquals(Theme.SYSTEM, key.fromStored("MISSING"))
        assertEquals(Theme.SYSTEM, key.fromStored(null))
    }

    @Test
    fun `choice rejects a default outside its values`() {
        var threw = false
        try {
            SettingKey.Choice(
                "appearance.theme",
                Theme.SYSTEM,
                persistentListOf(Theme.LIGHT, Theme.DARK),
            )
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `text set defaults are immutable`() {
        val key =
            SettingKey.TextSet(
                "groups.expanded",
                persistentSetOf("news"),
                SettingsFile.DEVICE,
            )
        assertEquals(setOf("news"), key.default)
    }
}
