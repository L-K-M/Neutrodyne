// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.podcast

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":feature:podcast", ModuleInfo.PATH)
    }
}
