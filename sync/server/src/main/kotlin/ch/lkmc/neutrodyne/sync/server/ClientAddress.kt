// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

/**
 * Resolves the calling client's address and transport scheme (10 Ktor setup): Ktor's
 * XForwardedHeaders plugin is not installed because it offers no check of the immediate peer
 * against a trusted list, so `X-Forwarded-For` and `X-Forwarded-Proto` are believed only when the
 * TCP peer is in `NEUTRODYNE_SERVER_TRUSTED_PROXIES` or loopback, and the client is then the
 * right-most chain entry that is neither a trusted proxy nor loopback. An entry that is not an
 * IP literal ends the walk and the peer address is used.
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
        // Walk left past hops that are trusted like the peer itself (N13): trusted proxies and
        // loopback. The first entry that is neither is the client only when it is an IP
        // literal; anything else ends the walk — looking further left would trust
        // client-supplied text.
        var client: String? = null
        for (entry in chain.asReversed()) {
            val address = IpLiterals.parse(entry) ?: break
            if (address.isLoopbackAddress || trustedProxies.any { cidr -> cidr.matches(address) }) continue
            client = entry
            break
        }

        // A trusted proxy may forward several proto values; the right-most is the nearest hop.
        val proto = forwardedProto.lastOrNull()?.trim()?.lowercase()
        return Resolved(address = client ?: peerHost, secureTransport = proto == ServerConfig.HTTPS_SCHEME)
    }
}
