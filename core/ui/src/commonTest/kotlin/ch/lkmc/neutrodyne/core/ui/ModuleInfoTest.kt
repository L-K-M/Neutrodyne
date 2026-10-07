// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":core:ui", ModuleInfo.PATH)
    }
}
