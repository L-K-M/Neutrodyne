// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import kotlin.test.Test
import kotlin.test.assertTrue

class ServerVersionTest {
    @Test
    fun versionIsNeverBlank() {
        assertTrue(ServerVersion.current().isNotBlank())
    }
}
