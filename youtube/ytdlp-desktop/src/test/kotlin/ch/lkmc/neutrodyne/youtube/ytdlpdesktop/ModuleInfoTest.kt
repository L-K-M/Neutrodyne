// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlpdesktop

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":youtube:ytdlp-desktop", ModuleInfo.PATH)
    }
}
