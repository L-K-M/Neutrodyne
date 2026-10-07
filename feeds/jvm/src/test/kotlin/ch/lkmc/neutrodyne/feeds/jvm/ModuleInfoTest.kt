// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":feeds:jvm", ModuleInfo.PATH)
    }
}
