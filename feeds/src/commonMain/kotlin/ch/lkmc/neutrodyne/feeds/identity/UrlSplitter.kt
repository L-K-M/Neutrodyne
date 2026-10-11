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
) {
    // The generated data-class toString would print secrets into logs and crash reports; a
    // Basic-auth token may sit in the username with an empty password, so both are masked.
    override fun toString(): String = "UrlUserInfo(username=<redacted>, password=<redacted>)"
}

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
    // WHATWG removes ASCII tab/CR/LF anywhere in the URL before parsing; match that so a feed
    // URL copied out of HTML with a stray control still gets an identity. The `any` scan keeps
    // the common case allocation-free.
    val clean =
        if (raw.any { it == '\t' || it == '\n' || it == '\r' }) {
            raw.filterNot { it == '\t' || it == '\n' || it == '\r' }
        } else {
            raw
        }
    val withoutFragment = clean.trim().substringBefore('#')

    val schemeMatch = schemePrefix.find(withoutFragment)
    val scheme = schemeMatch?.groupValues?.get(1)?.lowercase()
    val afterScheme = withoutFragment.substring(schemeMatch?.range?.last?.plus(1) ?: 0)

    val specialHttp = scheme == "http" || scheme == "https"
    // WHATWG ignores every leading `/` or `\` after `scheme:` for a special scheme, so
    // `http:/x`, `http:\\x` and `http:x` all fetch from host `x` (OkHttp agrees); other
    // schemes keep RFC 3986's `//`-only authority.
    val authorityText =
        if (specialHttp) {
            afterScheme.dropWhile { it == '/' || it == '\\' }
        } else if (afterScheme.startsWith("//")) {
            afterScheme.substring(2)
        } else {
            null
        }
    val hasAuthority = authorityText != null
    val rest = authorityText ?: afterScheme

    val userInfo: String?
    val host: String?
    val port: String?
    val pathAndQuery: String

    if (hasAuthority) {
        // For http(s), `\` ends the authority exactly like `/`: WHATWG URL and the fetch client
        // (OkHttp) both treat it as a path separator, so an `@` after it is path text, not
        // userinfo — `https://good.com\@evil.com/` is fetched from good.com.
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || (specialHttp && it == '\\') }
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
                if (close > 0) {
                    // After `]` only `:digits` may follow; other residue is invalid (the JDK's
                    // `URI` rejects it) and must not silently drop into the parts.
                    val afterBracket = hostPort.substring(close + 1)
                    val candidatePort = afterBracket.substringAfter(':')
                    val validPortFollows =
                        afterBracket.isEmpty() ||
                            (afterBracket.startsWith(':') && candidatePort.all { it in '0'..'9' })
                    host = if (validPortFollows) hostPort.substring(0, close + 1) else null
                    port = if (validPortFollows) candidatePort.takeIf { it.isNotEmpty() } else null
                } else {
                    host = null
                    port = null
                }
            } else {
                val colon = hostPort.indexOf(':')
                if (colon >= 0) {
                    val candidatePort = hostPort.substring(colon + 1)
                    if (candidatePort.contains(':')) {
                        // Not a host:port pair (e.g. a bare IPv6 literal "2001:db8::1") —
                        // leaving host as "2001" would mint a bogus identity for a malformed URL.
                        host = null
                        port = null
                    } else {
                        host = hostPort.substring(0, colon)
                        port = candidatePort.takeIf { it.isNotEmpty() }
                    }
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
