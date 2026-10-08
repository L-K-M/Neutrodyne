// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerVersionTest {
    @Test
    fun versionIsNeverBlank() {
        assertTrue(ServerVersion.current().isNotBlank())
    }

    @Test
    fun `gradle's unspecified and blank versions count as absent`() {
        assertNull(ServerVersion.usableVersion("unspecified"))
        assertNull(ServerVersion.usableVersion("  "))
        assertEquals("1.2.3", ServerVersion.usableVersion("1.2.3"))
    }
}
