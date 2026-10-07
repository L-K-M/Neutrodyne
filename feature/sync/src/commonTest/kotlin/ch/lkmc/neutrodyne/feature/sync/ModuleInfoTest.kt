// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":feature:sync", ModuleInfo.PATH)
    }
}
