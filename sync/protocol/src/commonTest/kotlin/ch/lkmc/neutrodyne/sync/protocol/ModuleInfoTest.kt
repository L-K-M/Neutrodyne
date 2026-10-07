// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":sync:protocol", ModuleInfo.PATH)
    }
}
