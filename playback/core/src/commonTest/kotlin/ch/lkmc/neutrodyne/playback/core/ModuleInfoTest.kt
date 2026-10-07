// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":playback:core", ModuleInfo.PATH)
    }
}
