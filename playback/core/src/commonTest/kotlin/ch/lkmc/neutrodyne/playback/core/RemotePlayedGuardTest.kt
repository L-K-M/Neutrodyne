// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 06 Sync interplay rule 3: which loaded items a remote mark-played has frozen. */
class RemotePlayedGuardTest {
    @Test
    fun freezeAndLiftMembership() {
        val guard = RemotePlayedGuard()
        assertFalse(guard.isFrozen(7))
        assertTrue(guard.allowsSaves(7))

        guard.freeze(7)
        assertTrue(guard.isFrozen(7))
        assertFalse(guard.allowsSaves(7), "the save gate reads the freeze inside the transaction")

        guard.lift(7)
        assertFalse(guard.isFrozen(7))
        assertTrue(guard.allowsSaves(7))
    }

    @Test
    fun releaseDropsOnlyOneContribution() {
        val guard = RemotePlayedGuard()
        guard.freeze(7)
        guard.freeze(7)

        guard.release(7)
        assertTrue(guard.isFrozen(7), "a second freeze still owns the mark")

        guard.release(7)
        assertFalse(guard.isFrozen(7))
        assertTrue(guard.allowsSaves(7))
    }

    @Test
    fun aRolledBackFreezeNeverReleasesACommittedOne() {
        val guard = RemotePlayedGuard()
        // Page A commits its mark; page B's identical mark then rolls back.
        guard.freeze(7)
        guard.freeze(7)

        guard.release(7)
        assertTrue(guard.isFrozen(7), "the committed transaction's freeze stays")
    }

    @Test
    fun releasingAnUnfrozenItemIsANoOp() {
        val guard = RemotePlayedGuard()
        guard.release(7)
        assertFalse(guard.isFrozen(7))
    }

    @Test
    fun currentChangedLiftsEverythingButTheNewCurrentItem() {
        val guard = RemotePlayedGuard()
        guard.freeze(7)
        guard.freeze(8)

        guard.currentChanged(8)
        assertFalse(guard.isFrozen(7), "the item stopped being current")
        assertTrue(guard.isFrozen(8))

        guard.currentChanged(null)
        assertFalse(guard.isFrozen(8), "nothing loaded: every freeze lifts")
    }

    @Test
    fun currentChangedNeverFreezesAnUnfrozenItem() {
        val guard = RemotePlayedGuard()
        guard.currentChanged(9)
        assertFalse(guard.isFrozen(9))
    }

    @Test
    fun frozenPublishesTheMembership() =
        runTest {
            val guard = RemotePlayedGuard()
            guard.freeze(7)
            assertEquals(setOf(7L), guard.frozen.first())
            guard.release(7)
            assertEquals(emptySet(), guard.frozen.first())
        }
}
