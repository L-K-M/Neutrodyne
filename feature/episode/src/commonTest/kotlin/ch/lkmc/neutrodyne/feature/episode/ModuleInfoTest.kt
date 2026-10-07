// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.episode

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":feature:episode", ModuleInfo.PATH)
    }
}
