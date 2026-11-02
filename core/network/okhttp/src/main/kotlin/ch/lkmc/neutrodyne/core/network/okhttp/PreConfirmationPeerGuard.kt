// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import ch.lkmc.neutrodyne.core.model.PRE_CONFIRMATION_HEADER
import okhttp3.Interceptor
import okhttp3.Response
import java.net.InetSocketAddress

/**
 * The M7 actual-peer guard (01 Interceptors; 03 note 2026-11-02). Requests the user never
 * submitted — add previews, autodiscovery candidates and probes, directory hits and their
 * artwork — carry [PRE_CONFIRMATION_HEADER] from the caller's layer. As a **network**
 * interceptor this runs once the connection exists and before the request is written, and
 * again on every follow-up hop: it promotes the header to the [PreConfirmationCall] tag,
 * strips it from the wire request and refuses the call when the connected peer is a
 * local-network address. The string-level `LocalDestination` check upstream stays as an
 * early fast path — it cannot see DNS answers or redirect targets, this can.
 *
 * `LocalNetworkUnsupportedException` is the same outcome the literal-local refusal produces,
 * so the UI shows its usual "tap to fetch this local address" / refusal state.
 */
internal class PreConfirmationPeerGuard(
    private val isLocalPeer: (InetSocketAddress) -> Boolean,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        if (request.header(PRE_CONFIRMATION_HEADER) != null) {
            request =
                request
                    .newBuilder()
                    .removeHeader(PRE_CONFIRMATION_HEADER)
                    .tag(PreConfirmationCall::class.java, PreConfirmationCall)
                    .build()
        }
        if (request.tag(PreConfirmationCall::class.java) == null) return chain.proceed(request)
        val peer = chain.connection()?.route()?.socketAddress
        if (peer == null || isLocalPeer(peer)) throw LocalNetworkUnsupportedException(request.url.host)
        return chain.proceed(request)
    }
}

/** The request tag marking a pre-confirmation call; tags survive OkHttp follow-ups. */
internal object PreConfirmationCall
