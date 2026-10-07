// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":feature:player", ModuleInfo.PATH)
    }
}
