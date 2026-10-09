// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class ProtocolVersionTest {
    @Test
    fun protocolVersionIsV1() {
        // 10 Versioning: v1 is the only wire version until an incompatible change bumps it.
        assertEquals(1, PROTOCOL_VERSION)
    }
}
