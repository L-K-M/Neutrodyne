// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** The exactly-once gate of [DbusDesktopPortal.chooseDirectory]'s outcomes. */
class OnceOnlyTest {
    @Test
    fun aClaimedGateNeverClaimsAgain() {
        val gate = OnceOnly()

        assertThat(gate.claim()).isTrue()
        assertThat(gate.claim()).isFalse()
    }

    @Test
    fun exactlyOneCallerClaimsTheOutcome() {
        val gate = OnceOnly()
        val claims = AtomicInteger()
        val start = CyclicBarrier(THREADS)
        val threads =
            List(THREADS) {
                thread {
                    repeat(CLAIMS) {
                        start.await()
                        if (gate.claim()) claims.incrementAndGet()
                    }
                }
            }

        threads.forEach { it.join() }

        assertThat(claims.get()).isEqualTo(1)
    }

    private companion object {
        const val THREADS = 8
        const val CLAIMS = 1_000
    }
}
