// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.credentials

import ch.lkmc.neutrodyne.core.common.Origin
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer

/** `credential.origin` value of the user's Podcast Index key (02 credential). */
internal const val PODCAST_INDEX_ORIGIN = "podcastindex"

/** The `sync:<host>` namespace holding the sync token's credential row on Android (02, 10). */
internal const val SYNC_ORIGIN_PREFIX = "sync:"

/**
 * The canonical `credential.origin` form of a caller-supplied string: `scheme://host[:port]`,
 * lowercase host, the scheme's default port omitted (02 credential). `UrlNormalizer.origin`
 * normalises http(s) input — including whole URLs, whose path drops off — and returns null for
 * the `podcastindex` and `sync:<host>` namespaces, which pass through verbatim.
 */
internal fun storedOrigin(input: String): String = UrlNormalizer.origin(input) ?: input

/**
 * Parses a stored `credential.origin` back into an [Origin] with 80/443 filled in, so it equals
 * `Origin.of(request.url)` (03 in-memory map). Returns null for the `podcastindex`/`sync:` rows —
 * they never enter the HTTP map, so `AuthInterceptor` can never send a sync token or the Podcast
 * Index key to a feed host — and for anything that is not a bare `scheme://host[:port]`.
 */
internal fun storedOriginToOrigin(stored: String): Origin? {
    val separator = stored.indexOf("://")
    if (separator <= 0) return null
    val scheme = stored.substring(0, separator)
    if (scheme != "http" && scheme != "https") return null
    val authority = stored.substring(separator + 3)
    if (authority.isEmpty()) return null
    // A stored origin is an authority only — no path, query, fragment or userinfo.
    if (authority.any { it == '/' || it == '?' || it == '#' || it == '@' || it.isWhitespace() }) {
        return null
    }
    val host: String
    val port: Int
    if (authority.startsWith("[")) {
        // Bracketed IP-literal: the host runs to `]`, an optional `:port` may follow.
        val close = authority.indexOf(']')
        if (close < 0) return null
        host = authority.substring(1, close)
        val rest = authority.substring(close + 1)
        port =
            when {
                rest.isEmpty() -> defaultPort(scheme)
                rest.startsWith(":") -> rest.substring(1).toIntOrNull() ?: return null
                else -> return null
            }
    } else {
        val colon = authority.lastIndexOf(':')
        if (colon < 0) {
            host = authority
            port = defaultPort(scheme)
        } else {
            host = authority.substring(0, colon)
            port = authority.substring(colon + 1).toIntOrNull() ?: return null
        }
    }
    if (host.isEmpty() || port !in 1..MAX_PORT) return null
    return Origin(scheme, host, port)
}

private fun defaultPort(scheme: String): Int = if (scheme == "http") HTTP_DEFAULT_PORT else HTTPS_DEFAULT_PORT

private const val HTTP_DEFAULT_PORT = 80
private const val HTTPS_DEFAULT_PORT = 443
private const val MAX_PORT = 65_535
