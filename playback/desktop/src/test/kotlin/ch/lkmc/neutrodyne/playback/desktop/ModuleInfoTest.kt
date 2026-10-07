// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":playback:desktop", ModuleInfo.PATH)
    }
}
