// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.model.TlsKind
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.cancellation.CancellationException

/**
 * OkHttp/transport exception → `NetError` (01 Network error taxonomy). Walks the cause chain — a
 * wrapped cause classifies by what it wraps. [connected] is the caller's `NetworkMonitor` read,
 * used to turn a DNS or socket failure into `NetError.Offline` when the device is disconnected.
 *
 * Callers must rethrow `CancellationException` **before** calling this (as
 * `NetErrorClassifier.classify` does): a cancelled coroutine is control flow, not a network error.
 * `NetError.Cancelled` is reserved for OkHttp's own `IOException("Canceled")`, e.g. a call whose
 * deadline expired.
 */
object JvmNetErrors {
    /** Max cause-chain hops — enough for real chains, immune to a cyclic `cause`. */
    private const val MAX_CAUSE_DEPTH = 32

    /**
     * Ktor's timeout exceptions live in `ktor-client-core`, which this island cannot see. Its
     * `ConnectTimeoutException` subclasses `java.net.ConnectException` and its
     * `SocketTimeoutException` subclasses `java.net.SocketTimeoutException`, so they must match
     * by name **before** the socket rows below.
     */
    private val ktorTimeoutNames =
        setOf(
            "HttpRequestTimeoutException",
            "ConnectTimeoutException",
            "SocketTimeoutException",
        )

    fun classify(
        e: Throwable,
        connected: Boolean,
    ): NetError {
        if (e is CancellationException) throw e
        var cause: Throwable? = e
        var depth = 0
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            val result = classifyOne(cause, connected)
            if (result != null) return result
            cause = cause.cause
            depth++
        }
        return NetError.Other(e::class.simpleName ?: "Throwable")
    }

    /** The single-hop classifier; `null` when [cause] is unclassifiable and the walk continues. */
    private fun classifyOne(
        cause: Throwable,
        connected: Boolean,
    ): NetError? =
        when {
            // Must precede UnknownHostException: it subclasses it.
            cause is LocalNetworkUnsupportedException -> {
                NetError.LocalNetworkUnsupported
            }

            cause.javaClass.simpleName in ktorTimeoutNames -> {
                NetError.Timeout
            }

            cause is SocketTimeoutException -> {
                NetError.Timeout
            }

            cause is InterruptedIOException && cause.message?.contains("timeout") == true -> {
                NetError.Timeout
            }

            cause is UnknownHostException -> {
                if (connected) NetError.DnsFailure else NetError.Offline
            }

            // ConnectException and NoRouteToHostException both subclass SocketException.
            cause is SocketException -> {
                if (connected) NetError.ConnectionFailed else NetError.Offline
            }

            cause is SSLHandshakeException -> {
                NetError.Tls(tlsKind(cause))
            }

            cause is SSLException -> {
                NetError.Tls(TlsKind.HANDSHAKE)
            }

            cause is IOException && cause.message == "Canceled" -> {
                NetError.Cancelled
            }

            else -> {
                null
            }
        }

    /**
     * Distinguishes the TLS kinds (01): Certificate-Transparency policy failures carry
     * "Certificate Transparency" in the chain; a `CertPathValidatorException` or "Trust anchor"
     * message is an untrusted-certificate failure; anything else is a generic handshake error.
     */
    private fun tlsKind(e: SSLHandshakeException): TlsKind {
        var cause: Throwable? = e
        var depth = 0
        var sawValidatorFailure = false
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            val message = cause.message.orEmpty()
            if (message.contains("Certificate Transparency", ignoreCase = true)) {
                return TlsKind.CERTIFICATE_TRANSPARENCY
            }
            if (cause is CertPathValidatorException || message.contains("Trust anchor")) {
                sawValidatorFailure = true
            }
            cause = cause.cause
            depth++
        }
        return if (sawValidatorFailure) TlsKind.UNTRUSTED_CERTIFICATE else TlsKind.HANDSHAKE
    }
}
