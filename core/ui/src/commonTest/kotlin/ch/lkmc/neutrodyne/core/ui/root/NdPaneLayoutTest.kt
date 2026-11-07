// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NdPaneLayoutTest {
    @Test fun panesUseContentWidthAfterChromeAndPlayer() {
        assertEquals(1, ndPaneLayout(695, 96, false, false).partitions)
        assertEquals(2, ndPaneLayout(696, 96, false, false).partitions)
        assertEquals(1, ndPaneLayout(1055, 96, true, false).partitions)
        assertEquals(2, ndPaneLayout(1056, 96, true, false).partitions)
        assertEquals(2, ndPaneLayout(1707, 96, true, false).partitions)
        assertEquals(3, ndPaneLayout(1708, 96, true, false).partitions)
        assertEquals(3, ndPaneLayout(1296, 96, false, false).partitions)
    }

    @Test fun panelStartsAt840AndHiddenPanelReturnsItsWidth() {
        assertFalse(ndPaneLayout(839, 96, true, false).playerPanel)
        val panel = ndPaneLayout(840, 96, true, false)
        assertTrue(panel.playerPanel)
        assertEquals(384, panel.contentWidthDp)
        val hidden = ndPaneLayout(840, 96, true, true)
        assertFalse(hidden.playerPanel)
        assertEquals(744, hidden.contentWidthDp)
        assertEquals(2, hidden.partitions)
    }
}
