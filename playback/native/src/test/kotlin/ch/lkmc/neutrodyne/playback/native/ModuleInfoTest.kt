// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.native

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":playback:native", ModuleInfo.PATH)
    }
}
