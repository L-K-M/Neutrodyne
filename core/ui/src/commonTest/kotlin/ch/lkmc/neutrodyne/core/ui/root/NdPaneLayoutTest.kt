// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

import kotlin.test.Test
import kotlin.test.assertEquals

class NdPaneLayoutTest {
    @Test fun panesUseContentWidthAfterChrome() {
        assertEquals(1, ndPaneLayout(695, 96).partitions)
        assertEquals(2, ndPaneLayout(696, 96).partitions)
        assertEquals(2, ndPaneLayout(1295, 96).partitions)
        assertEquals(3, ndPaneLayout(1296, 96).partitions)
        assertEquals(744, ndPaneLayout(840, 96).contentWidthDp)
    }

    @Test fun noPlayerPanelTakesWidthAtAnySize() {
        // The radio home is the only full player (08 Owner revision, 2026-11-15).
        assertEquals(1200, ndPaneLayout(1200, 0).contentWidthDp)
        assertEquals(3, ndPaneLayout(1200, 0).partitions)
    }
}
