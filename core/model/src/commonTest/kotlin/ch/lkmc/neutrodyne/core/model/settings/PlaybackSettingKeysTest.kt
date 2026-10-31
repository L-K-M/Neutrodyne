// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.model.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackSettingKeysTest {
    @Test fun speedValidationAndNormalizationUseTheWholeFiniteGrid() {
        for (step in 0..50) {
            assertTrue(PlaybackSettingKeys.isValidSpeed(0.5f + step * 0.05f))
        }
        for (bad in listOf(0.49f, 3.01f, 1.51f, 1.5004f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFalse(PlaybackSettingKeys.isValidSpeed(bad), "$bad")
        }
        assertTrue(PlaybackSettingKeys.isValidSpeed(1.5f + 0.000_000_4f))
        assertEquals(0.5f, PlaybackSettingKeys.normalizeSpeed(-3f))
        assertEquals(3f, PlaybackSettingKeys.normalizeSpeed(9f))
        assertEquals(1.55f, PlaybackSettingKeys.normalizeSpeed(1.53f))
        assertEquals(1.8f, PlaybackSettingKeys.normalizeSpeed(1.8f))
        assertFailsWith<IllegalArgumentException> { PlaybackSettingKeys.normalizeSpeed(Float.NaN) }
    }

    @Test fun presetsRequireTwoToEightValidSpeedsAndAcceptAnyOrder() {
        val parse = PlaybackSettingKeys::parseSpeedPresets
        assertEquals(listOf(0.8f, 1f, 1.2f, 1.5f, 1.8f, 2f), parse(PlaybackSettingKeys.SPEED_PRESETS.default))
        assertEquals(listOf(0.75f, 1.25f, 2.5f), parse("2.5, 0.75,1.25"))
        assertEquals(listOf(1f, 1f), parse("1,1"))
        assertEquals(8, parse("0.5,0.75,1.0,1.25,1.5,1.75,2.0,3.0")?.size)
        for (bad in listOf(
            "",
            "1",
            "1,",
            "1,abc",
            "1,9.9",
            "1,1.555",
            "1;2",
            "[1,2]",
            "1,,2",
            "0.5,0.6,0.7,0.8,0.9,1,1.1,1.2,1.3",
        )) {
            assertNull(parse(bad), bad)
        }
    }

    @Test fun registryValidationChecksTypesChoicesAndIntervalCacheRanges() {
        val valid: List<Pair<SettingKey<*>, Any>> =
            listOf(
                PlaybackSettingKeys.SLEEP_LAST_MINUTES to 1,
                PlaybackSettingKeys.SLEEP_LAST_MINUTES to 240,
                PlaybackSettingKeys.HARDWARE_BUTTONS to HardwareButtons.SKIP,
                PlaybackSettingKeys.SKIP_BACK_MS to 5_000L,
                PlaybackSettingKeys.SKIP_FORWARD_MS to 90_000L,
                PlaybackSettingKeys.STREAM_CACHE_MB to 1_000,
                PlaybackSettingKeys.STREAM_ON_METERED to StreamOnMetered.ASK,
            )
        val invalid: List<Pair<SettingKey<*>, Any>> =
            listOf(
                PlaybackSettingKeys.SLEEP_LAST_MINUTES to 0,
                PlaybackSettingKeys.SLEEP_LAST_MINUTES to 241,
                PlaybackSettingKeys.SLEEP_LAST_MINUTES to 30L,
                PlaybackSettingKeys.HARDWARE_BUTTONS to "SKIP",
                PlaybackSettingKeys.SKIP_BACK_MS to 7_000L,
                PlaybackSettingKeys.SKIP_FORWARD_MS to 5_000L,
                PlaybackSettingKeys.STREAM_CACHE_MB to 333,
                PlaybackSettingKeys.SPEED to 1.5,
                PlaybackSettingKeys.STREAM_ON_METERED to "ASK",
            )
        valid.forEach { (key, value) -> assertTrue(AllSettingKeys.acceptsValue(key, value), key.name) }
        invalid.forEach { (key, value) -> assertFalse(AllSettingKeys.acceptsValue(key, value), key.name) }
    }
}
