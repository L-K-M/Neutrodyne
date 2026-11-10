// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.youtube.api.EngineUpdateOutcome
import ch.lkmc.neutrodyne.youtube.engine.CompileMarker
import ch.lkmc.neutrodyne.youtube.engine.EngineSettingsSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `state.value = state.value.copy(...)` is a read-modify-write: a write that lands between the
 * sample and the store is silently lost. The journey race is `setEnabled` on the caller thread
 * against `recordStartFailures` on the engine's writer coroutine (YtDlpEngine persists failure
 * identity after the health gate). [GatedSnapshotFlow] parks the writer thread between its
 * sample and its store, so the interleaving is deterministic instead of a timing flake.
 */
class FakeEngineSettingsAtomicityTest {
    private val marker = CompileMarker(42, "2026.08.19", "3.14")

    @Test fun recordStartFailuresKeepsConcurrentEnabledWrite() {
        val settings =
            raced(
                writer = { runBlocking { it.recordStartFailures(marker, 3) } },
                caller = { it.setEnabled(false) },
            )
        val snapshot = settings.state.value
        assertFalse(snapshot.enabled)
        assertEquals(3, snapshot.startFailures)
        assertEquals(marker, snapshot.failureIdentity)
        assertEquals(listOf(marker to 3), settings.writes)
    }

    @Test fun recordCheckKeepsConcurrentEnabledWrite() {
        val settings =
            raced(
                writer = { runBlocking { it.recordCheck(7L, EngineUpdateOutcome.UpToDate) } },
                caller = { it.setEnabled(false) },
            )
        val snapshot = settings.state.value
        assertFalse(snapshot.enabled)
        assertEquals(7L, snapshot.lastCheckAtMs)
        assertEquals(EngineUpdateOutcome.UpToDate, snapshot.lastOutcome)
        assertEquals(listOf<Pair<Long, EngineUpdateOutcome>>(7L to EngineUpdateOutcome.UpToDate), settings.checks)
    }

    @Test fun recordHeartbeatKeepsConcurrentEnabledWrite() {
        val settings =
            raced(
                writer = { runBlocking { it.recordHeartbeat(9L) } },
                caller = { it.setEnabled(false) },
            )
        val snapshot = settings.state.value
        assertFalse(snapshot.enabled)
        assertEquals(9L, snapshot.heartbeatAtMs)
        assertEquals(listOf(9L), settings.heartbeats)
    }

    @Test fun setEnabledKeepsConcurrentRecordWrite() {
        val settings =
            raced(
                writer = { it.setEnabled(false) },
                caller = { runBlocking { it.recordStartFailures(marker, 3) } },
            )
        val snapshot = settings.state.value
        assertFalse(snapshot.enabled)
        assertEquals(3, snapshot.startFailures)
        assertEquals(marker, snapshot.failureIdentity)
    }

    private fun raced(
        writer: (FakeEngineSettings) -> Unit,
        caller: (FakeEngineSettings) -> Unit,
    ): FakeEngineSettings {
        val slot = AtomicReference<FakeEngineSettings>()
        val writerError = AtomicReference<Throwable>()
        val thread =
            Thread {
                try {
                    writer(checkNotNull(slot.get()))
                } catch (error: Throwable) {
                    writerError.set(error)
                }
            }
        val flow = GatedSnapshotFlow(MutableStateFlow(EngineSettingsSnapshot()), thread)
        val settings = FakeEngineSettings(flow)
        slot.set(settings)
        thread.start()
        assertTrue(flow.writeArrived.await(10, TimeUnit.SECONDS), "writer never reached its state write")
        caller(settings)
        flow.releaseWrite.countDown()
        thread.join(10_000)
        assertFalse(thread.isAlive, "writer still parked after release")
        writerError.get()?.let { throw it }
        return settings
    }
}

/**
 * Parks [writerThread]'s first mutating call after it sampled the snapshot: [writeArrived]
 * signals the parked write, [releaseWrite] lets it land. The gate fires once, so CAS retries
 * after a lost race pass ungated.
 */
private class GatedSnapshotFlow(
    private val inner: MutableStateFlow<EngineSettingsSnapshot>,
    private val writerThread: Thread,
) : MutableStateFlow<EngineSettingsSnapshot> by inner {
    val writeArrived = CountDownLatch(1)
    val releaseWrite = CountDownLatch(1)
    private val armed = AtomicBoolean(true)

    private fun gate() {
        if (Thread.currentThread() !== writerThread || !armed.compareAndSet(true, false)) return
        writeArrived.countDown()
        releaseWrite.await()
    }

    override var value: EngineSettingsSnapshot
        get() = inner.value
        set(new) {
            gate()
            inner.value = new
        }

    override fun compareAndSet(
        expect: EngineSettingsSnapshot,
        update: EngineSettingsSnapshot,
    ): Boolean {
        gate()
        return inner.compareAndSet(expect, update)
    }

    override suspend fun emit(value: EngineSettingsSnapshot) {
        gate()
        inner.emit(value)
    }

    override fun tryEmit(value: EngineSettingsSnapshot): Boolean {
        gate()
        return inner.tryEmit(value)
    }
}
