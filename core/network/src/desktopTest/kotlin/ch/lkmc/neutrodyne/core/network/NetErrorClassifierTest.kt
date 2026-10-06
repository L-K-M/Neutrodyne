// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network

import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.model.NetError
import com.google.common.truth.Truth.assertThat
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * `NetErrorClassifier` (01 Testing): every taxonomy row through the real desktop binding —
 * including Ktor's wrapper types — with the monitor connected and disconnected, the re-check side
 * effect, and `CancellationException` escaping unclassified.
 */
class NetErrorClassifierTest {
    private val connected = listOf(ndInterface("eth0", addresses = arrayOf("93.184.216.34")))
    private val disconnected = listOf(ndInterface("eth0", up = false, addresses = arrayOf("93.184.216.34")))

    private fun newClassifier(source: FakeInterfaceSource): Pair<PlatformNetErrorClassifier, DesktopNetworkMonitor> {
        val scope = CoroutineScope(EmptyCoroutineContext)
        val monitor = DesktopNetworkMonitor(scope, FakePowerMonitor(), source)
        return PlatformNetErrorClassifier(monitor) to monitor
    }

    @Test
    fun `unknown host follows the monitor state`() {
        val (classifier) = newClassifier(FakeInterfaceSource(connected))
        assertThat(classifier.classify(UnknownHostException("x"))).isEqualTo(NetError.DnsFailure)

        val (offline) = newClassifier(FakeInterfaceSource(disconnected))
        assertThat(offline.classify(UnknownHostException("x"))).isEqualTo(NetError.Offline)
    }

    @Test
    fun `ktor timeout wrappers classify as Timeout`() {
        val (classifier) = newClassifier(FakeInterfaceSource(connected))
        assertThat(classifier.classify(HttpRequestTimeoutException("http://x/", 5_000)))
            .isEqualTo(NetError.Timeout)
        assertThat(classifier.classify(ConnectTimeoutException("http://x/", SocketTimeoutException())))
            .isEqualTo(NetError.Timeout)
    }

    @Test
    fun `socket failures map and re-check the monitor`() {
        val source = FakeInterfaceSource(connected)
        val (classifier) = newClassifier(source)
        val queriesBefore = source.queries
        assertThat(classifier.classify(ConnectException("refused"))).isEqualTo(NetError.ConnectionFailed)
        // ConnectionFailed (like Offline and DnsFailure) triggers an immediate re-check.
        assertThat(source.queries).isGreaterThan(queriesBefore)
    }

    @Test
    fun `a failure that is not offline-shaped does not re-check`() {
        val source = FakeInterfaceSource(connected)
        val (classifier) = newClassifier(source)
        val queriesBefore = source.queries
        assertThat(classifier.classify(java.io.IOException("other"))).isEqualTo(NetError.Other("IOException"))
        assertThat(source.queries).isEqualTo(queriesBefore)
    }

    @Test
    fun `a live refused connection classifies by monitor state`() =
        runBlocking {
            val (classifier) = newClassifier(FakeInterfaceSource(connected))
            val clients = newHttpClients()
            val thrown =
                try {
                    runCatching { clients.client(HttpClientKind.FEED).get("http://127.0.0.1:1/") }
                        .exceptionOrNull()
                } finally {
                    clients.closeAll()
                }
            assertThat(thrown).isNotNull()
            val error = classifier.classify(thrown!!)
            assertThat(error).isAnyOf(NetError.ConnectionFailed, NetError.Timeout)

            // Same failure with a disconnected monitor is Offline — and the interface walk is real.
            val offlineSource = FakeInterfaceSource(disconnected)
            val (offline) = newClassifier(offlineSource)
            val thrown2 = runCatching { java.net.Socket("127.0.0.1", 1).close() }.exceptionOrNull()
            assertThat(thrown2).isInstanceOf(SocketException::class.java)
            assertThat(offline.classify(thrown2!!)).isEqualTo(NetError.Offline)
        }

    @Test
    fun `CancellationException is rethrown`() {
        val (classifier) = newClassifier(FakeInterfaceSource(connected))
        assertThrows(CancellationException::class.java) {
            classifier.classify(CancellationException("job gone"))
        }
    }
}
