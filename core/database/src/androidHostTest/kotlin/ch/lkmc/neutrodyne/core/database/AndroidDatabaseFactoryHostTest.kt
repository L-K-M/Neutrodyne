// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `AndroidDatabaseFactory`'s marker and pending-stamp files under Robolectric (02 Database
 * builder and connections): round-trip set/clear on a real sandbox filesystem, and clearing an
 * absent record is a no-op rather than a failure (the delete result is only checked while the
 * file still exists).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidDatabaseFactoryHostTest {
    @Test
    fun markerAndPendingStampRoundTripAndClearWhenAbsent() {
        val factory = AndroidDatabaseFactory(RuntimeEnvironment.getApplication())

        assertFalse(factory.quarantineMarker)
        assertNull(factory.pendingQuarantine)

        factory.quarantineMarker = true
        assertTrue(factory.quarantineMarker)
        factory.pendingQuarantine = "1700000000000"
        assertEquals("1700000000000", factory.pendingQuarantine)

        factory.quarantineMarker = false
        factory.pendingQuarantine = null
        assertFalse(factory.quarantineMarker)
        assertNull(factory.pendingQuarantine)

        // Clearing absent records must not throw — a pending stamp survives without a marker.
        factory.quarantineMarker = false
        factory.pendingQuarantine = null
    }
}
