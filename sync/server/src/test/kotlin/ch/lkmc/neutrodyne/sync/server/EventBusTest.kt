// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventBusTest {
    private fun principal(
        account: Long = 1,
        device: String = "d",
        token: String = "t",
    ) = AccountPrincipal(account, AccountRole.USER, TokenKind.DEVICE, token, device)

    private fun webPrincipal(
        account: Long = 1,
        token: String = "w",
    ) = AccountPrincipal(account, AccountRole.USER, TokenKind.WEB_SESSION, token, null)

    private fun changed(
        seq: Int,
        account: Long = 1,
    ) = ServerEvent.Changed(account, "c:00000001:$seq", "writer")

    @Test fun `no replay account isolation and subscriber cleanup`() =
        runBlocking<Unit> {
            EventBus({ true }).use { bus ->
                bus.publish(changed(1))
                val received = mutableListOf<ServerEvent>()
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        bus.subscribe(principal()).take(1).collect {
                            received +=
                                it
                        }
                    }
                repeat(3) { yield() }
                assertEquals(1, bus.streamCount())
                assertTrue(received.isEmpty())
                bus.publish(changed(2, account = 2))
                repeat(3) { yield() }
                assertTrue(received.isEmpty())
                bus.publish(changed(3))
                withTimeout(2000) { collector.join() }
                assertEquals(listOf<ServerEvent>(changed(3)), received)
                assertEquals(0, bus.streamCount())
            }
        }

    @Test fun `slow subscriber receives only newest buffered change`() =
        runBlocking<Unit> {
            EventBus({ true }).use { bus ->
                val first = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val received = mutableListOf<ServerEvent>()
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        bus.subscribe(principal()).take(2).collect {
                            received += it
                            if (received.size == 1) {
                                first.complete(Unit)
                                release.await()
                            }
                        }
                    }
                repeat(3) { yield() }
                bus.publish(changed(1))
                withTimeout(2000) { first.await() }
                (2..100).forEach { bus.publish(changed(it)) }
                repeat(3) { yield() }
                release.complete(Unit)
                withTimeout(2000) { collector.join() }
                assertEquals(listOf<ServerEvent>(changed(1), changed(100)), received)
            }
        }

    @Test fun `revocation is never dropped behind a burst and only closes matching streams`() =
        runBlocking<Unit> {
            EventBus({ true }).use { bus ->
                val a = bus.open(principal())
                val b = bus.open(principal(device = "other", token = "other"))
                val otherAccount = bus.open(principal(account = 2))
                val end = async(start = CoroutineStart.UNDISPATCHED) { a.events.collect() }
                repeat(3) { yield() }
                repeat(100) { bus.publish(changed(it)) }
                bus.publish(ServerEvent.Revoked(1, tokenHash = "t"))
                withTimeout(2000) { end.await() }
                assertEquals(2, bus.streamCount())
                b.close()
                otherAccount.close()
                a.close()
                assertEquals(0, bus.streamCount())
            }
        }

    @Test fun `stream caps are two per device ten per account and cleanup frees slots`() =
        runBlocking<Unit> {
            EventBus({ true }).use { bus ->
                val streams = mutableListOf<EventStream>()
                repeat(5) { d -> repeat(2) { streams += bus.open(principal(device = "d$d")) } }
                assertFailsWith<AuthThrottled> { bus.open(principal(device = "sixth")) }
                // A device at its own cap evicts its oldest stream instead of being throttled.
                streams += bus.open(principal(device = "d0"))
                assertEquals(10, bus.streamCount())
                streams[1].close()
                streams += bus.open(principal(device = "sixth"))
                streams.forEach { it.close() }
                assertEquals(0, bus.streamCount())
            }
        }

    @Test fun `a device over its stream cap replaces its oldest stream`() =
        runBlocking<Unit> {
            EventBus({ true }).use { bus ->
                val oldest = bus.open(principal(device = "d0", token = "oldest"))
                val second = bus.open(principal(device = "d0", token = "second"))
                val other = bus.open(principal(device = "d1", token = "other"))
                val oldestEnd = async(start = CoroutineStart.UNDISPATCHED) { oldest.events.collect() }
                val secondEnd = async(start = CoroutineStart.UNDISPATCHED) { second.events.collect() }
                val otherEnd = async(start = CoroutineStart.UNDISPATCHED) { other.events.collect() }
                repeat(3) { yield() }
                bus.open(principal(device = "d0", token = "newest"))
                withTimeout(2000) { oldestEnd.await() }
                assertFalse(secondEnd.isCompleted)
                assertFalse(otherEnd.isCompleted)
                assertEquals(3, bus.streamCount())
                second.close()
                other.close()
                withTimeout(2000) { secondEnd.await() }
                withTimeout(2000) { otherEnd.await() }
            }
        }

    @Test fun `a principal without a device at the stream cap is throttled`() =
        runBlocking<Unit> {
            EventBus({ true }).use { bus ->
                bus.open(webPrincipal(token = "w1"))
                bus.open(webPrincipal(token = "w2"))
                assertFailsWith<AuthThrottled> { bus.open(webPrincipal(token = "w3")) }
                assertEquals(2, bus.streamCount())
            }
        }

    @Test fun `the account stream cap still throttles a new device`() =
        runBlocking<Unit> {
            EventBus({ true }).use { bus ->
                repeat(5) { d -> repeat(2) { bus.open(principal(device = "d$d")) } }
                assertFailsWith<AuthThrottled> { bus.open(principal(device = "sixth")) }
                assertEquals(10, bus.streamCount())
            }
        }

    @Test fun `a device at the account cap replaces its own oldest stream`() =
        runBlocking<Unit> {
            EventBus({ true }).use { bus ->
                val oldest = bus.open(principal(device = "d0", token = "oldest"))
                bus.open(principal(device = "d0", token = "second"))
                repeat(4) { d -> repeat(2) { bus.open(principal(device = "d${d + 1}")) } }
                assertEquals(10, bus.streamCount())
                val oldestEnd = async(start = CoroutineStart.UNDISPATCHED) { oldest.events.collect() }
                repeat(3) { yield() }
                bus.open(principal(device = "d0", token = "newest"))
                withTimeout(2000) { oldestEnd.await() }
                assertEquals(10, bus.streamCount())
            }
        }

    @Test fun `revocation during validity recheck and cancelled subscribers cannot leak slots`() =
        runBlocking<Unit> {
            val checking = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            EventBus({
                checking.complete(Unit)
                finish.await()
                false
            }).use { bus ->
                val result = async { runCatching { bus.open(principal()) } }
                checking.await()
                bus.publish(ServerEvent.Revoked(1, deviceId = "d"))
                finish.complete(Unit)
                assertTrue(result.await().isFailure)
                assertEquals(0, bus.streamCount())
            }
            EventBus({ true }).use { bus ->
                val collector = launch(start = CoroutineStart.UNDISPATCHED) { bus.subscribe(principal()).collect() }
                repeat(3) { yield() }
                collector.cancel()
                collector.join()
                assertEquals(0, bus.streamCount())
                bus.close()
                assertFailsWith<AuthThrottled> { bus.open(principal()) }
            }
        }

    @Test fun `SQLite auth and record rollback cannot publish notifications`() =
        runBlocking<Unit> {
            val published = mutableListOf<ServerEvent>()
            SqliteSyncStore(tempDataDir(), published::add).use { store ->
                assertFailsWith<IllegalStateException> {
                    store.writeAuth {
                        emit(ServerEvent.Revoked(1))
                        error("rollback")
                    }
                }
                assertTrue(published.isEmpty())
                store.writeAuth { emit(ServerEvent.Revoked(1)) }
                assertEquals(listOf<ServerEvent>(ServerEvent.Revoked(1)), published)
                published.clear()
                store.writeAuth { createAccount("alice", AccountRole.USER, null, "00000001", 0, 1000) }
                assertFailsWith<IllegalStateException> {
                    store.write(1) {
                        emit(changed(1))
                        error("rollback")
                    }
                }
                assertTrue(published.isEmpty())
                store.write(1) { emit(changed(2)) }
                assertEquals(listOf<ServerEvent>(changed(2)), published)
            }
        }
}
