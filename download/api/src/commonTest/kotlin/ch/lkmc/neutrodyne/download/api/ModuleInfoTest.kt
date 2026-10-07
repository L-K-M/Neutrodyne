// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.api

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":download:api", ModuleInfo.PATH)
    }
}
