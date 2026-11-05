// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

/**
 * [LinuxIdleSleepInhibitor]'s hold lifecycle over a fake portal connection (the real D-Bus
 * round trip is the private-bus test): the inhibition is taken exactly once per hold,
 * released exactly once, a missing portal is logged instead of thrown, and a failing
 * release never stops a later hold.
 */
class LinuxIdleSleepInhibitorTest {
    @Test
    fun acquireHoldsOnceAndReleaseClosesOnce() {
        val handle = RecordingHandle()
        val portal = FakePortal(handle)
        val inhibitor = LinuxIdleSleepInhibitor { portal }

        inhibitor.acquire("Neutrodyne is playing")
        inhibitor.acquire("Neutrodyne is playing")

        assertThat(portal.inhibits).isEqualTo(listOf("Neutrodyne is playing"))

        inhibitor.release()
        inhibitor.release()

        assertThat(handle.closes).isEqualTo(1)
        assertThat(inhibitor.failure.value).isNull()
    }

    @Test
    fun aMissingPortalIsLoggedNotThrownAndRetriedLater() {
        val portal = FailingPortal()
        val inhibitor = LinuxIdleSleepInhibitor { portal }

        inhibitor.acquire("Neutrodyne is playing") // must not throw

        assertThat(inhibitor.failure.value).contains("portal is down")
        assertThat(portal.attempts).isEqualTo(1)

        portal.recover()
        inhibitor.acquire("Neutrodyne is playing")

        assertThat(inhibitor.failure.value).isNull()
        assertThat(portal.attempts).isEqualTo(2)
    }

    @Test
    fun aFailingReleaseNeverStopsTheNextHold() {
        val handle = RecordingHandle()
        val portal = FakePortal(handle)
        val inhibitor = LinuxIdleSleepInhibitor { portal }

        inhibitor.acquire("Neutrodyne is playing")
        handle.failClose = true
        inhibitor.release()

        assertThat(inhibitor.failure.value).contains("releasing idle-sleep inhibition failed")

        handle.failClose = false
        inhibitor.acquire("Neutrodyne is playing")
        inhibitor.release()

        assertThat(handle.closes).isEqualTo(2)
    }

    @Test
    fun closeReleasesTheHoldAndShutsTheConnection() {
        val handle = RecordingHandle()
        val portal = FakePortal(handle)
        val inhibitor = LinuxIdleSleepInhibitor { portal }

        inhibitor.acquire("Neutrodyne is playing")
        inhibitor.close()

        assertThat(handle.closes).isEqualTo(1)
        assertThat(portal.connectionCloses).isEqualTo(1)
    }

    private class FakePortal(
        private val handle: RecordingHandle = RecordingHandle(),
    ) : LinuxIdleSleepInhibitor.PortalConnection {
        val inhibits = mutableListOf<String>()

        var connectionCloses = 0
            private set

        override fun inhibit(reason: String): AutoCloseable {
            inhibits += reason
            return handle
        }

        override fun close() {
            connectionCloses++
        }
    }

    private class FailingPortal : LinuxIdleSleepInhibitor.PortalConnection {
        var recovered = false
        var attempts = 0
            private set

        override fun inhibit(reason: String): AutoCloseable {
            attempts += 1
            check(recovered) { "portal is down" }
            return RecordingHandle()
        }

        fun recover() {
            recovered = true
        }

        override fun close() = Unit
    }

    private class RecordingHandle : AutoCloseable {
        var closes = 0
            private set

        var failClose = false

        override fun close() {
            closes += 1
            check(!failClose) { "Request.Close refused" }
        }
    }
}
