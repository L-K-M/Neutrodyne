// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.identity

/**
 * Credentials split out of a URL's userinfo (`https://user:pass@host/feed`, percent-decoded). Feeds-local
 * type: `:feeds` cannot see `:core:model`'s `BasicCredentials` (01 rule 8), so `:core:data` maps this
 * when it stores credentials (03 deviation, 2026-10-06).
 */
public data class UrlUserInfo(
    val username: String,
    val password: String,
)

/** One URL broken into parts by [splitLenient], before any normalisation. */
internal class UrlParts(
    val scheme: String?,
    val userInfo: String?,
    val host: String?,
    val port: String?,
    val path: String,
    val query: String?,
)

private val schemePrefix = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*):""")
private const val DEFAULT_HTTP_PORT = "80"
private const val DEFAULT_HTTPS_PORT = "443"

/**
 * A small hand-written RFC 3986 splitter in common code (03 URL normalisation): the JDK's `URI`
 * is JVM-only and rejects unescaped spaces and other characters real feed URLs contain. Lenient: it never
 * throws; parts that are missing are null or empty.
 */
internal fun splitLenient(raw: String): UrlParts {
    val withoutFragment = raw.trim().substringBefore('#')

    val schemeMatch = schemePrefix.find(withoutFragment)
    val scheme = schemeMatch?.groupValues?.get(1)?.lowercase()
    val afterScheme = withoutFragment.substring(schemeMatch?.range?.last?.plus(1) ?: 0)

    val hasAuthority = afterScheme.startsWith("//")
    val rest = if (hasAuthority) afterScheme.substring(2) else afterScheme

    val userInfo: String?
    val host: String?
    val port: String?
    val pathAndQuery: String

    if (hasAuthority) {
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' }
        val authority = if (authorityEnd < 0) rest else rest.substring(0, authorityEnd)
        pathAndQuery = if (authorityEnd < 0) "" else rest.substring(authorityEnd)

        var hostPort = authority
        val atIndex = authority.lastIndexOf('@')
        userInfo =
            if (atIndex >= 0) {
                hostPort = authority.substring(atIndex + 1)
                authority.substring(0, atIndex)
            } else {
                null
            }

        if (hostPort.isNotEmpty()) {
            if (hostPort.startsWith("[")) {
                val close = hostPort.indexOf(']')
                host =
                    if (close > 0) {
                        hostPort.substring(0, close + 1)
                    } else {
                        null
                    }
                if (close in 0 until hostPort.length - 1 && hostPort[close + 1] == ':') {
                    port = hostPort.substring(close + 2).takeIf { it.isNotEmpty() }
                } else {
                    port = null
                }
            } else {
                val colon = hostPort.indexOf(':')
                if (colon >= 0) {
                    host = hostPort.substring(0, colon)
                    port = hostPort.substring(colon + 1).takeIf { it.isNotEmpty() }
                } else {
                    host = hostPort
                    port = null
                }
            }
        } else {
            host = null
            port = null
        }
    } else {
        userInfo = null
        host = null
        port = null
        pathAndQuery = rest
    }

    val path = pathAndQuery.substringBefore('?')
    val query = pathAndQuery.substringAfter('?', "").takeIf { it.isNotEmpty() }

    return UrlParts(scheme, userInfo, host, port, path, query)
}

/**
 * Whether [port] is dropped. Scheme-free identity drops both well-known ports (03 URL
 * normalisation); scheme-specific forms ([UrlNormalizer.origin]) drop only the scheme's default.
 */
internal fun isDefaultPort(
    scheme: String?,
    port: String?,
    schemeFree: Boolean,
): Boolean =
    if (schemeFree) {
        port == DEFAULT_HTTP_PORT || port == DEFAULT_HTTPS_PORT
    } else {
        when (scheme) {
            "http" -> port == DEFAULT_HTTP_PORT
            "https" -> port == DEFAULT_HTTPS_PORT
            else -> false
        }
    }
