// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.impl

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":sync:impl", ModuleInfo.PATH)
    }
}
