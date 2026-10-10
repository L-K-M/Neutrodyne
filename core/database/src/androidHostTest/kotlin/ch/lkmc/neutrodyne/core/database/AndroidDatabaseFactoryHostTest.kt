// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun markerSetTrueIsIdempotent() {
        val factory = AndroidDatabaseFactory(RuntimeEnvironment.getApplication())
        factory.quarantineMarker = true
        // A crash-retry path sets the marker again; an existing marker must not fail.
        factory.quarantineMarker = true
        assertTrue(factory.quarantineMarker)
        factory.quarantineMarker = false
        assertFalse(factory.quarantineMarker)
    }

    @Test
    fun quarantineRejectsANonNumericStamp() {
        // The stamp is a directory name read back from on-disk state; anything that is not
        // epoch millis must fail before any filesystem move.
        val factory = AndroidDatabaseFactory(RuntimeEnvironment.getApplication())
        assertFailsWith<IllegalArgumentException> { factory.quarantine("../escape") }
        assertFailsWith<IllegalArgumentException> { factory.quarantine("not-a-stamp") }
    }

    @Test
    fun pruneKeepsTheNumericallyNewestFreshCopy() {
        // "999999999999" sorts above "1000000000000" as a string but is the older stamp.
        val factory = AndroidDatabaseFactory(RuntimeEnvironment.getApplication())
        val quarantineDir = File(factory.databasePath).parentFile!!.resolve("quarantine")
        val older = quarantineDir.resolve("999999999999")
        val newer = quarantineDir.resolve("1000000000000")
        assertTrue(older.mkdirs() || older.isDirectory)
        assertTrue(newer.mkdirs() || newer.isDirectory)

        factory.pruneQuarantine(now = 1_000_100_000_000L)

        assertTrue(newer.exists(), "the numerically newest fresh copy must be kept")
        assertFalse(older.exists(), "the older copy must be pruned")
    }
}
