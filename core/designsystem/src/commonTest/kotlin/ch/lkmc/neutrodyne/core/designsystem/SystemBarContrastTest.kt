// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class SystemBarContrastTest {
    @Test
    fun actualStripChoosesHigherContrastIcons() {
        assertEquals(StatusBarAppearance.DarkIcons, statusBarAppearanceFor(Color.White))
        assertEquals(StatusBarAppearance.LightIcons, statusBarAppearanceFor(Color.Black))
        assertEquals(StatusBarAppearance.LightIcons, statusBarAppearanceFor(Color(0xFF202020)))
        // This midtone is below .5 luminance but black icons still have the better contrast.
        assertEquals(StatusBarAppearance.DarkIcons, statusBarAppearanceFor(Color(0xFF808080)))
        assertEquals(StatusBarAppearance.LightIcons, statusBarAppearanceFor(Color(0xFF606060)))
    }
}
