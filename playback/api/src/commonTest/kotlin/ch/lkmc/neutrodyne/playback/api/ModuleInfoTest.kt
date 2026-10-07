// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.api

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":playback:api", ModuleInfo.PATH)
    }
}
