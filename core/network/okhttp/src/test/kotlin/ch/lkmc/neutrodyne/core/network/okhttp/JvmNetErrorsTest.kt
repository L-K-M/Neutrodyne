// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.model.TlsKind
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.cancellation.CancellationException

// Ktor's timeout types are unreachable from the island, so it matches by simple name — these
// fakes have the right names and superclass shapes to prove the mapping without Ktor.
private class ConnectTimeoutException(
    message: String,
) : ConnectException(message)

private class HttpRequestTimeoutException(
    message: String,
) : IOException(message)

class JvmNetErrorsTest {
    private fun classify(
        e: Throwable,
        connected: Boolean = true,
    ): NetError = JvmNetErrors.classify(e, connected)

    @Test
    fun `lan guard maps to LocalNetworkUnsupported`() {
        assertThat(classify(LocalNetworkUnsupportedException("nas")))
            .isEqualTo(NetError.LocalNetworkUnsupported)
        // Even wrapped — it must win over the UnknownHostException rows it subclasses.
        assertThat(classify(IOException(LocalNetworkUnsupportedException("nas"))))
            .isEqualTo(NetError.LocalNetworkUnsupported)
    }

    @Test
    fun `unknown host maps by connectivity`() {
        assertThat(classify(UnknownHostException("x"))).isEqualTo(NetError.DnsFailure)
        assertThat(classify(UnknownHostException("x"), connected = false)).isEqualTo(NetError.Offline)
    }

    @Test
    fun `timeouts map to Timeout`() {
        assertThat(classify(SocketTimeoutException("read timed out"))).isEqualTo(NetError.Timeout)
        assertThat(classify(InterruptedIOException("timeout"))).isEqualTo(NetError.Timeout)
        // Ktor's wrappers, matched by simple name before the socket rows.
        assertThat(classify(ConnectTimeoutException("connect timed out"))).isEqualTo(NetError.Timeout)
        assertThat(classify(HttpRequestTimeoutException("request timed out"))).isEqualTo(NetError.Timeout)
    }

    @Test
    fun `socket failures map by connectivity`() {
        assertThat(classify(ConnectException("refused"))).isEqualTo(NetError.ConnectionFailed)
        assertThat(classify(NoRouteToHostException())).isEqualTo(NetError.ConnectionFailed)
        assertThat(classify(SocketException("Connection reset"))).isEqualTo(NetError.ConnectionFailed)
        assertThat(classify(ConnectException("refused"), connected = false)).isEqualTo(NetError.Offline)
        assertThat(classify(SocketException("broken"), connected = false)).isEqualTo(NetError.Offline)
    }

    @Test
    fun `tls kinds`() {
        assertThat(classify(SSLHandshakeException("generic handshake failure")))
            .isEqualTo(NetError.Tls(TlsKind.HANDSHAKE))
        assertThat(classify(SSLHandshakeException("Trust anchor for certification path not found.")))
            .isEqualTo(NetError.Tls(TlsKind.UNTRUSTED_CERTIFICATE))
        assertThat(
            classify(
                SSLHandshakeException("cert").apply {
                    initCause(CertPathValidatorException("unable to find valid certification path"))
                },
            ),
        ).isEqualTo(NetError.Tls(TlsKind.UNTRUSTED_CERTIFICATE))
        assertThat(
            classify(
                SSLHandshakeException("cert").apply {
                    initCause(CertificateException("Certificate Transparency policy failed"))
                },
            ),
        ).isEqualTo(NetError.Tls(TlsKind.CERTIFICATE_TRANSPARENCY))
        // A non-handshake SSLException is still a handshake-kind failure.
        assertThat(classify(SSLException("engine closed"))).isEqualTo(NetError.Tls(TlsKind.HANDSHAKE))
    }

    @Test
    fun `okhttp canceled call maps to Cancelled`() {
        assertThat(classify(IOException("Canceled"))).isEqualTo(NetError.Cancelled)
    }

    @Test
    fun `anything else is Other, classified by the top exception`() {
        assertThat(classify(IOException("boom"))).isEqualTo(NetError.Other("IOException"))
        assertThat(classify(RuntimeException())).isEqualTo(NetError.Other("RuntimeException"))
        assertThat(classify(InterruptedIOException("interrupted by another thread")))
            .isEqualTo(NetError.Other("InterruptedIOException"))
    }

    @Test
    fun `wrapped causes classify by what they wrap`() {
        assertThat(classify(IOException(SocketTimeoutException()))).isEqualTo(NetError.Timeout)
        assertThat(classify(RuntimeException(IOException(UnknownHostException()))))
            .isEqualTo(NetError.DnsFailure)
        // The outermost classifiable element wins: a "Canceled" wrapper stays Cancelled even
        // when it wraps a timeout.
        assertThat(classify(IOException("Canceled", SocketTimeoutException())))
            .isEqualTo(NetError.Cancelled)
    }

    @Test
    fun `a cyclic cause chain terminates`() {
        val a = Exception("a")
        val b = Exception("b")
        a.initCause(b)
        b.initCause(a)
        assertThat(classify(a)).isEqualTo(NetError.Other("Exception"))
    }

    @Test
    fun `CancellationException is rethrown, never classified`() {
        assertThrows(CancellationException::class.java) {
            JvmNetErrors.classify(CancellationException("job cancelled"), connected = true)
        }
    }
}
