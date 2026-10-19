// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.ui.root

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoverSearchFocusTest {
    @Test
    fun detachedSearchHasNoFocusTarget() {
        val focus = DiscoverSearchFocus()
        assertFalse(focus.request())
        var requests = 0
        val action = {
            requests++
            Unit
        }
        focus.register(action)
        assertTrue(focus.request())
        assertEquals(1, requests)
        focus.unregister(action)
        assertFalse(focus.request())
    }

    @Test
    fun outgoingScreenCannotClearTheNewScreensTarget() {
        val focus = DiscoverSearchFocus()
        val old = {}
        var requests = 0
        val current = {
            requests++
            Unit
        }
        focus.register(old)
        focus.register(current)
        focus.unregister(old)
        assertTrue(focus.request())
        assertEquals(1, requests)
    }
}
