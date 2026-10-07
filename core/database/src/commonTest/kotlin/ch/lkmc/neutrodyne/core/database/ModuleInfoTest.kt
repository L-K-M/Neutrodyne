// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.database

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":core:database", ModuleInfo.PATH)
    }
}
