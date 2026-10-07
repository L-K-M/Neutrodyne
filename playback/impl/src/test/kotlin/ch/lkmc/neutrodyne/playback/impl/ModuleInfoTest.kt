// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":playback:impl", ModuleInfo.PATH)
    }
}
