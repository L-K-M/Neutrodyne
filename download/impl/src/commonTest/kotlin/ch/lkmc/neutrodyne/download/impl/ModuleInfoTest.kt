// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":download:impl", ModuleInfo.PATH)
    }
}
