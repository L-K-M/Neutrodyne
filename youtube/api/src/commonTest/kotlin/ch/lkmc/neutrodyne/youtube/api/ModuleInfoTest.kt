// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.api

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":youtube:api", ModuleInfo.PATH)
    }
}
