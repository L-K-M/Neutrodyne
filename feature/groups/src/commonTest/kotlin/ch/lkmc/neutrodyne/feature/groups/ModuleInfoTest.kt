// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.groups

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":feature:groups", ModuleInfo.PATH)
    }
}
