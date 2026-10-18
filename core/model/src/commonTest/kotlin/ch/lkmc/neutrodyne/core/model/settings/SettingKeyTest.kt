// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

import ch.lkmc.neutrodyne.core.model.ExportLayout
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

    /**
     * 05 Settings: export-dialog memories and `auto_snapshot_enabled` are portable (they
     * travel in backups) but never synced; the snapshot bookkeeping and `installation_id`
     * stay on the device — `installation_id` is the foreign-snapshot guard.
     */
    @Test fun backupKeysHaveDesignDefaultsAndClassification() {
        val backupKeys = BackupSettingKeys.ALL
        assertEquals(9, backupKeys.size)
        assertTrue(AllSettingKeys.list.containsAll(backupKeys))
        val portableNames =
            setOf("backup.opml_layout", "backup.opml_include_youtube", "backup.auto_snapshot_enabled")
        for (key in backupKeys) {
            assertEquals(
                if (key.name in portableNames) SettingsFile.PORTABLE else SettingsFile.DEVICE,
                key.file,
            )
            assertEquals(false, key.synced)
        }
        assertEquals(ExportLayout.GROUPED, BackupSettingKeys.OPML_LAYOUT.default)
        assertEquals(listOf(ExportLayout.GROUPED, ExportLayout.FLAT), BackupSettingKeys.OPML_LAYOUT.values)
        assertEquals(true, BackupSettingKeys.OPML_INCLUDE_YOUTUBE.default)
        assertEquals(true, BackupSettingKeys.AUTO_SNAPSHOT_ENABLED.default)
        assertEquals("", BackupSettingKeys.INSTALLATION_ID.default)
        assertEquals(SettingsFile.DEVICE, BackupSettingKeys.INSTALLATION_ID.file)
    }

    /**
     * 06 Settings: all eight `playback.*` keys are portable; only the five that describe the
     * user's taste are synced; `pause_for_navigation`, `stream_on_metered` and
     * `stream_cache_mb` describe this device, so they stay out of sync.
     */
    @Test fun playbackKeysHaveDesignDefaultsAndSyncClassification() {
        val playbackKeys = PlaybackSettingKeys.ALL
        assertEquals(8, playbackKeys.size)
        assertTrue(AllSettingKeys.list.containsAll(playbackKeys))
        val synced =
            setOf(
                "playback.speed",
                "playback.skip_silence",
                "playback.speed_presets",
                "playback.skip_back_ms",
                "playback.skip_forward_ms",
            )
        for (key in playbackKeys) {
            assertEquals(SettingsFile.PORTABLE, key.file, key.name)
            assertEquals(key.name in synced, key.synced, key.name)
        }
        assertEquals(1.0f, PlaybackSettingKeys.SPEED.default)
        assertEquals(false, PlaybackSettingKeys.SKIP_SILENCE.default)
        assertEquals("0.8,1.0,1.2,1.5,1.8,2.0", PlaybackSettingKeys.SPEED_PRESETS.default)
        assertEquals(10_000L, PlaybackSettingKeys.SKIP_BACK_MS.default)
        assertEquals(30_000L, PlaybackSettingKeys.SKIP_FORWARD_MS.default)
        assertEquals(true, PlaybackSettingKeys.PAUSE_FOR_NAVIGATION.default)
        assertEquals(StreamOnMetered.ALLOW, PlaybackSettingKeys.STREAM_ON_METERED.default)
        assertEquals(
            listOf(StreamOnMetered.ALLOW, StreamOnMetered.ASK, StreamOnMetered.NEVER),
            PlaybackSettingKeys.STREAM_ON_METERED.values,
        )
        assertEquals(500, PlaybackSettingKeys.STREAM_CACHE_MB.default)
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
