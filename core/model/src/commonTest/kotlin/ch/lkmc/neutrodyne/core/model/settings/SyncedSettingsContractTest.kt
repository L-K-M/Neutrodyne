// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.model.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** MS0 checks existing classification; later feature keys must extend this explicit contract. */
class SyncedSettingsContractTest {
    @Test
    fun currentPortableSyncAllowListIsExplicit() {
        val expected =
            setOf(
                "playback.speed",
                "playback.skip_silence",
                "playback.skip_back_ms",
                "playback.skip_forward_ms",
                "playback.speed_presets",
                "feeds.show_notes_images",
                "feeds.backfill_paged_feeds",
                "groups.show_ungrouped_tab",
                "groups.all_filter_flags",
                "groups.all_media_filter",
                "groups.all_hide_older_than_days",
                "groups.ungrouped_filter_flags",
                "groups.ungrouped_media_filter",
                "groups.ungrouped_hide_older_than_days",
            )
        val synced = AllSettingKeys.list.filter { it.synced }
        assertEquals(expected, synced.map { it.name }.toSet())
        assertTrue(synced.all { it.file == SettingsFile.PORTABLE })
        assertTrue(AllSettingKeys.list.flatMap(::validateSettingKey).isEmpty())
    }
}
