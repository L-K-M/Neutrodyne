// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":desktop:system", ModuleInfo.PATH)
    }
}
