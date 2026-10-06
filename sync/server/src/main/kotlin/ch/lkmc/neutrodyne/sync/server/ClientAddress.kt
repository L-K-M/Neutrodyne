// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

/**
 * Resolves the calling client's address and transport scheme (10 Ktor setup): Ktor's
 * XForwardedHeaders plugin is not installed because it offers no check of the immediate peer
 * against a trusted list, so `X-Forwarded-For` and `X-Forwarded-Proto` are believed only when the
 * TCP peer is in `NEUTRODYNE_SERVER_TRUSTED_PROXIES`, and the client is then the right-most
 * address in the chain that is not itself a trusted proxy.
 */
internal class ClientAddress(
    private val trustedProxies: List<IpCidr>,
) {
    /** The resolved client address and whether its hop to this server was HTTPS. */
    data class Resolved(
        val address: String,
        val secureTransport: Boolean,
    )

    fun resolve(
        peerHost: String,
        forwardedFor: List<String>,
        forwardedProto: List<String>,
    ): Resolved {
        // N13: peers that are "neither loopback nor a trusted proxy" are untrusted; a loopback
        // peer is the local reverse proxy (or local software), so its forwarded headers count.
        val peer = IpLiterals.parse(peerHost)
        val peerIsTrusted =
            isLoopbackHost(peerHost) ||
                (peer != null && trustedProxies.any { cidr -> cidr.matches(peer) })
        if (!peerIsTrusted) {
            // An untrusted (or unparseable) peer: forwarded headers would be client-forgeable.
            return Resolved(address = peerHost, secureTransport = false)
        }

        val chain =
            forwardedFor
                .flatMap { header -> header.split(',') }
                .map { entry -> entry.trim() }
                .filter { entry -> entry.isNotEmpty() }
        val client =
            chain.lastOrNull { entry ->
                val address = IpLiterals.parse(entry)
                address == null || trustedProxies.none { cidr -> cidr.matches(address) }
            }

        // A trusted proxy may forward several proto values; the right-most is the nearest hop.
        val proto = forwardedProto.lastOrNull()?.trim()?.lowercase()
        return Resolved(address = client ?: peerHost, secureTransport = proto == ServerConfig.HTTPS_SCHEME)
    }
}
