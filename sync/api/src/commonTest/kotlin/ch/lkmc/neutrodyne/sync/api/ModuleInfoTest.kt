// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.api

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":sync:api", ModuleInfo.PATH)
    }
}
