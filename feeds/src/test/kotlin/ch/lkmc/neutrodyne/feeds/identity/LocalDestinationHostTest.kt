// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.identity

import kotlin.test.Test
import kotlin.test.assertTrue

class LocalDestinationHostTest {
    @Test
    fun numericHostClassifierIsTotalEvenWithoutNormalizerBounds() {
        // Public URLs currently hit the safe raw fallback instead. Exercise this defensive
        // boundary directly without widening production visibility just for the regression.
        val classify = LocalDestination::class.java.getDeclaredMethod("isLocalHost", String::class.java)
        classify.isAccessible = true
        for (host in listOf("99999999999.0.0.1", "1.99999999999.0.1", "1.0.99999999999.1", "1.0.0.99999999999")) {
            assertTrue(classify.invoke(LocalDestination, host) as Boolean, host)
        }
    }
}
