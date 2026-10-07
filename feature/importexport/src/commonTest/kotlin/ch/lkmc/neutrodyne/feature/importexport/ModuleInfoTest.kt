// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.importexport

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":feature:importexport", ModuleInfo.PATH)
    }
}
