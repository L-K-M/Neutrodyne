// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":core:artwork", ModuleInfo.PATH)
    }
}
