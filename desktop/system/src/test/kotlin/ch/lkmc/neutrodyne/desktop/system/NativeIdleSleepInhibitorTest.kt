// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The native [IdleSleepInhibitor]s over the [PowerAssertion] seam: acquire holds, release
 * lets go, and the native library closes with the inhibitor. The OS calls' idempotency is
 * the native boundary test's (`tests/power_test.c`).
 */
class NativeIdleSleepInhibitorTest {
    @Test
    fun macAcquireHoldsAndReleaseLetsGo() {
        val assertion = RecordingAssertion()
        MacIdleSleepInhibitor(assertion).use { inhibitor ->
            inhibitor.acquire("playing")
            inhibitor.acquire("playing again")
            inhibitor.release()
        }

        assertThat(assertion.calls).containsExactly(true, true, false).inOrder()
        assertThat(assertion.closed).isTrue()
    }

    @Test
    fun windowsAcquireHoldsAndReleaseLetsGo() {
        val assertion = RecordingAssertion()
        WindowsIdleSleepInhibitor(assertion).use { inhibitor ->
            inhibitor.acquire("playing")
            inhibitor.release()
            inhibitor.release()
        }

        assertThat(assertion.calls).containsExactly(true, false, false).inOrder()
        assertThat(assertion.closed).isTrue()
    }

    private class RecordingAssertion : PowerAssertion {
        val calls = mutableListOf<Boolean>()
        var closed = false

        override fun keepAwake(on: Boolean) {
            calls += on
        }

        override fun close() {
            closed = true
        }
    }
}
