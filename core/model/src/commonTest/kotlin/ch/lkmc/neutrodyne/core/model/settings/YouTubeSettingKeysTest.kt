// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

import ch.lkmc.neutrodyne.core.model.YouTubeVariantBits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YouTubeSettingKeysTest {
    @Test fun defaultsAndBackupClassification() {
        assertTrue(YouTubeSettingKeys.SUGGEST_RSS.default)
        assertTrue(YouTubeSettingKeys.MARK_PLAYED_ON_OPEN.default)
        val portable =
            setOf(
                "youtube.suggest_rss",
                "youtube.mark_played_on_open",
                "youtube.engine_enabled",
                "youtube.audio_quality",
                "youtube.volume_levelling",
                "youtube.auto_download",
                "youtube.auto_download_keep_latest",
            )
        assertEquals(18, YouTubeSettingKeys.ALL.size)
        for (key in YouTubeSettingKeys.ALL) {
            assertTrue(key in AllSettingKeys.list)
            assertEquals(if (key.name in portable) SettingsFile.PORTABLE else SettingsFile.DEVICE, key.file)
            assertFalse(key.synced)
            assertTrue(validateSettingKey(key).isEmpty())
        }
        assertEquals(0L, YouTubeSettingKeys.FEED_OUTAGE_UNTIL.default)
        assertEquals(0L, YouTubeSettingKeys.FEED_RATE_LIMITED_UNTIL.default)
        assertEquals(0, YouTubeSettingKeys.FEED_OUTAGE_LEVEL.default)
        assertEquals(0, YouTubeSettingKeys.FEED_RATE_LIMIT_LEVEL.default)
        assertTrue(validateSettingKey(SettingKey.Bool("youtube.future", false, synced = true)).isNotEmpty())
    }

    @Test fun engineDefaultsAndRanges() {
        assertTrue(YouTubeSettingKeys.ENGINE_ENABLED.default)
        assertEquals(YouTubeAudioQuality.STANDARD, YouTubeSettingKeys.AUDIO_QUALITY.default)
        assertEquals(YouTubeAudioQuality.entries.toList(), YouTubeSettingKeys.AUDIO_QUALITY.values.toList())
        assertEquals(YouTubeAudioQuality.STANDARD, YouTubeSettingKeys.AUDIO_QUALITY.fromStored("future"))
        assertFalse(YouTubeSettingKeys.VOLUME_LEVELLING.default)
        assertFalse(YouTubeSettingKeys.AUTO_DOWNLOAD.default)
        assertEquals(2, YouTubeSettingKeys.AUTO_DOWNLOAD_KEEP_LATEST.default)
        for (n in -1..11) {
            assertEquals(
                n in 1..10,
                AllSettingKeys.acceptsValue(YouTubeSettingKeys.AUTO_DOWNLOAD_KEEP_LATEST, n),
            )
        }
        assertFalse(AllSettingKeys.acceptsValue(YouTubeSettingKeys.RATE_LIMIT_LEVEL, -1))
        assertFalse(AllSettingKeys.acceptsValue(YouTubeSettingKeys.BREAKER_OPEN_UNTIL, -1L))
        assertFalse(AllSettingKeys.acceptsValue(YouTubeSettingKeys.AUDIO_QUALITY, "OPUS"))
        assertTrue(YouTubeSettingKeys.ALL.none { it.name.contains("updates") || it.name.contains("last_check") })
    }

    @Test fun rowArtworkIsPortableButNeverSynced() {
        val key = AppearanceSettingKeys.YOUTUBE_ROW_ART
        assertTrue(key in AllSettingKeys.list)
        assertEquals(YouTubeRowArt.VIDEO_THUMBNAIL, key.default)
        assertEquals(YouTubeRowArt.entries.toList(), key.values.toList())
        assertEquals(SettingsFile.PORTABLE, key.file)
        assertFalse(key.synced)
        assertEquals(key.default, key.fromStored("FUTURE"))
    }

    @Test fun importMasksAllowSingleVariantsWhileSettingsKeepLongForm() {
        for (mask in -2..10) {
            assertEquals(mask in 1..7, YouTubeVariantBits.isValid(mask))
            assertEquals(mask in listOf(1, 3, 5, 7), YouTubeVariantBits.isValidForSettings(mask))
        }
    }
}
