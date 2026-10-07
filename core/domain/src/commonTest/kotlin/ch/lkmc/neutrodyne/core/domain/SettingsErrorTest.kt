// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pins the [SettingsError] values 08's inline-error handling switches on: `WriteFailed` stays a
 * singleton and `OutOfRange` carries and compares by the rejected key's name.
 */
class SettingsErrorTest {
    @Test
    fun outOfRangeCarriesAndComparesByTheKeyName() {
        assertEquals(SettingsError.OutOfRange("playback.speed"), SettingsError.OutOfRange("playback.speed"))
        assertNotEquals(SettingsError.OutOfRange("playback.speed"), SettingsError.OutOfRange("playback.skip_silence"))
    }

    @Test
    fun writeFailedDiffersFromAnyOutOfRange() {
        assertNotEquals<SettingsError>(SettingsError.WriteFailed, SettingsError.OutOfRange("playback.speed"))
    }
}
