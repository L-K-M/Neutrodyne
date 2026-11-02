// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import ch.lkmc.neutrodyne.core.model.PRE_CONFIRMATION_HEADER
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * The M7 actual-peer guard (01 Interceptors; 03 note 2026-11-02). Requests the user never
 * submitted — add previews, autodiscovery candidates and probes, directory hits and their
 * artwork — are tagged by [PreConfirmationRequestGuard]. This network interceptor checks the
 * actual socket before writing each hop, including a reused pooled connection. DNS and literal
 * checks prevent new local sockets; this remains the final check on the peer actually selected.
 *
 * `LocalNetworkUnsupportedException` is the same outcome the literal-local refusal produces,
 * so the UI shows its usual "tap to fetch this local address" / refusal state.
 */
internal class PreConfirmationPeerGuard(
    private val isLocalPeer: (InetSocketAddress) -> Boolean,
    private val destinations: PreConfirmationDestinations,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.tag(PreConfirmationCall::class.java) == null) return chain.proceed(request)
        val peer = chain.connection()?.socket()?.remoteSocketAddress as? InetSocketAddress
        if (peer == null || isLocalPeer(peer)) throw LocalNetworkUnsupportedException(request.url.host)
        val response = chain.proceed(request)
        // Reject literal-IP follow-ups before OkHttp bypasses DNS to connect to them.
        if (response.code in REDIRECT_STATUSES) {
            val target = response.header("Location")?.let(request.url::resolve)
            if (target != null) {
                try {
                    destinations.check(target)
                } catch (failure: IOException) {
                    response.close()
                    throw failure
                }
            }
        }
        return response
    }

    private companion object {
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    }
}

/** Removes the internal header before debug/network interceptors and covers the follow-up chain. */
internal class PreConfirmationRequestGuard(
    private val destinations: PreConfirmationDestinations,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.header(PRE_CONFIRMATION_HEADER) == null &&
            original.tag(PreConfirmationCall::class.java) == null
        ) {
            return chain.proceed(original)
        }
        val request =
            original
                .newBuilder()
                .removeHeader(PRE_CONFIRMATION_HEADER)
                .tag(PreConfirmationCall::class.java, PreConfirmationCall)
                .build()
        return PreConfirmationCall.protect {
            destinations.check(request.url)
            chain.proceed(request)
        }
    }
}

/** OkHttp skips Dns for IP literals, so check its already-decoded/IDNA-mapped HttpUrl too. */
internal class PreConfirmationDestinations(
    private val dns: Dns,
    private val isLocalPeer: (InetSocketAddress) -> Boolean,
) {
    fun check(url: HttpUrl) {
        val host = url.host.removeSuffix(".")
        val addresses =
            if (host.contains(':') || host.all { it in '0'..'9' || it == '.' }) {
                listOf(InetAddress.getByName(host))
            } else {
                dns.lookup(host)
            }
        if (addresses.any { isLocalPeer(InetSocketAddress(it, url.port)) }) {
            throw LocalNetworkUnsupportedException(host)
        }
    }
}

/** Checks the answers used for route selection, not just an earlier DNS snapshot. */
internal class PreConfirmationDns(
    private val delegate: Dns,
    private val isLocalPeer: (InetSocketAddress) -> Boolean,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (PreConfirmationCall.active && addresses.any { isLocalPeer(InetSocketAddress(it, 0)) }) {
            throw LocalNetworkUnsupportedException(hostname)
        }
        return addresses
    }
}

/**
 * Tags survive follow-ups; the thread scope makes the policy visible to the shared DNS chain.
 * OkHttp 5.5 calls blocking Dns.lookup during route planning on the interceptor's call thread,
 * before launching its fast-fallback connect tasks. Socket-count regressions pin that contract.
 * Always restore the scope, including on refusal/cancellation, so explicit calls stay allowed.
 */
internal object PreConfirmationCall {
    private val scope = ThreadLocal<Boolean>()
    val active: Boolean get() = scope.get() == true

    fun <T> protect(block: () -> T): T {
        val previous = scope.get()
        scope.set(true)
        try {
            return block()
        } finally {
            if (previous == null) scope.remove() else scope.set(previous)
        }
    }
}
