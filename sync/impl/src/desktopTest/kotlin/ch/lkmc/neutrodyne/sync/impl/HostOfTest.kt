// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import kotlin.test.Test
import kotlin.test.assertEquals

/** [hostOf]: the token origin's host, with the HTTP(S) scheme stripped in any letter case. */
class HostOfTest {
    @Test
    fun schemeCaseDoesNotEnterTheHost() {
        assertEquals("sync.example", hostOf("http://sync.example"))
        assertEquals("sync.example", hostOf("HTTP://sync.example"))
        assertEquals("sync.example", hostOf("HtTpS://sync.example"))
        assertEquals("sync.example", hostOf("https://sync.example/Path?Query=Token"))
    }
}
