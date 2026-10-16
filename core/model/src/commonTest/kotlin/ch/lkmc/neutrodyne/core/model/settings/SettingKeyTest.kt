// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

import ch.lkmc.neutrodyne.core.model.MediaFilter
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Registry rules from 01 DataStore files and typed setting keys. M0a registers the `appearance.*`
 * keys; the tests exercise every registry entry plus [validateSettingKey] directly, and keep
 * working as further areas land their `ALL` lists.
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

    @Test fun groupKeysHaveDesignDefaultsAndSyncClassification() {
        val groupKeys = GroupsSettingKeys.ALL
        assertEquals(9, groupKeys.size)
        assertTrue(AllSettingKeys.list.containsAll(groupKeys))
        for (key in groupKeys) {
            val visit = key.name.endsWith("last_viewed_at")
            assertEquals(if (visit) SettingsFile.DEVICE else SettingsFile.PORTABLE, key.file)
            assertEquals(!visit, key.synced)
        }
        assertEquals(false, GroupsSettingKeys.SHOW_UNGROUPED_TAB.default)
        assertEquals(0, GroupsSettingKeys.ALL_FILTER_FLAGS.default)
        assertEquals(0, GroupsSettingKeys.UNGROUPED_FILTER_FLAGS.default)
        assertEquals(0, GroupsSettingKeys.ALL_HIDE_OLDER_THAN_DAYS.default)
        assertEquals(0, GroupsSettingKeys.UNGROUPED_HIDE_OLDER_THAN_DAYS.default)
        assertEquals(MediaFilter.ALL, GroupsSettingKeys.ALL_MEDIA_FILTER.default)
        assertEquals(MediaFilter.ALL, GroupsSettingKeys.UNGROUPED_MEDIA_FILTER.default)
        assertEquals(MediaFilter.entries.toList(), GroupsSettingKeys.ALL_MEDIA_FILTER.values)
        assertEquals(MediaFilter.entries.toList(), GroupsSettingKeys.UNGROUPED_MEDIA_FILTER.values)
        assertEquals(0L, GroupsSettingKeys.ALL_LAST_VIEWED_AT.default)
        assertEquals(0L, GroupsSettingKeys.UNGROUPED_LAST_VIEWED_AT.default)
    }

    @Test fun selectionKeysAreRegisteredAndDeviceLocal() {
        val feed = AppearanceSettingKeys.FEEDS_SELECTED_SOURCE
        val library = AppearanceSettingKeys.LIBRARY_GROUP_FILTER
        assertEquals("ui.feeds_selected_source", feed.name)
        assertEquals("all", feed.default)
        assertEquals("ui.library_group_filter", library.name)
        assertEquals("", library.default)
        for (key in listOf(feed, library)) {
            assertTrue(key in AllSettingKeys.list)
            assertEquals(SettingsFile.DEVICE, key.file)
            assertEquals(false, key.synced)
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
