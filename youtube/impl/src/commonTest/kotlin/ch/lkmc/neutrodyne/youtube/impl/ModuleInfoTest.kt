// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.impl

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":youtube:impl", ModuleInfo.PATH)
    }
}
