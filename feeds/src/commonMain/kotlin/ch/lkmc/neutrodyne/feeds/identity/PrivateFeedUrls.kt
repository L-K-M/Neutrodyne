// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.identity

/**
 * The private-feed heuristic (03 Private feed URLs): token-in-URL feeds (Patreon, Supercast, Memberful
 * and similar) are secrets. False positives only cost a warning. Used by 05's export warning, the
 * "Private feed" chip and 10's link disclosure.
 */
public object PrivateFeedUrls {
    /** Query parameter names that mark a URL as private (case-insensitive). */
    private val tokenParameterNames =
        setOf(
            "token",
            "auth",
            "key",
            "apikey",
            "api_key",
            "secret",
            "sig",
            "signature",
            "access_token",
            "session",
            "sid",
            "uid",
            "user",
            "pass",
            "password",
            "hash",
            "subscriber",
            "member",
            "premium",
        )

    /** Hosts whose feeds are private by nature (heuristic list, extended from bug reports). */
    private val privateHosts = listOf("patreon.com", "supercast.tech", "memberful.com", "memberfulcontent.com")

    /** A long token in a path segment or query value; must contain both a letter and a digit. */
    private val longToken = Regex("""[A-Za-z0-9_-]{20,}""")

    /**
     * Click-tracking IDs are long and random-looking, but they are public share metadata
     * appended by the link sharer's platform — never feed secrets.
     */
    private val trackingParams =
        setOf(
            "fbclid",
            "gclid",
            "msclkid",
            "dclid",
            "ttclid",
            "twclid",
            "igshid",
            "yclid",
            "li_fat_id",
            "mc_eid",
            "mkt_tok",
            "_hsenc",
        )

    /** Whether [url] looks like a private (token-carrying) feed URL. */
    public fun looksPrivate(url: String): Boolean {
        val parts = splitLenient(url)

        // Credentials in the userinfo: the strongest signal.
        if (parts.userInfo != null) return true

        // A missing host skips only the host list: query and path token checks still run on
        // scheme-less or malformed input (a stored reference may carry the token anyway).
        val host = parts.host?.lowercase()
        if (host != null && privateHosts.any { host == it || host.endsWith(".$it") }) return true

        // Token-bearing query parameter names, then long tokens in any query value.
        val query = parts.query ?: ""
        if (query.split('&').any { param ->
                val name = param.substringBefore('=').lowercase()
                // Exact names, plus `_`-joined forms of the credential cores only
                // ("auth_token", "feed_token", "token_id", "basic_auth"): the `_` boundary
                // keeps "author"/"tokenize"/"monkey" clean, and widening it to the whole set
                // would flag generic names like "user_id" or "key_id" on public feeds.
                name in tokenParameterNames ||
                    name.startsWith("token_") || name.endsWith("_token") ||
                    name.startsWith("auth_") || name.endsWith("_auth")
            }
        ) {
            return true
        }
        if (looksLikeTokenQueryValue(query)) return true

        // Long tokens (≥20 chars) as part of any path segment ("/feeds/xKd93lskSKEa1zl4Tq8w/").
        // The permissive class deliberately keeps '-'/'_': UUID and base64url tokens carry them,
        // and a false positive on a hyphenated slug only costs a warning.
        return parts.path.split('/').any { segment -> tokenIn(segment) != null }
    }

    private fun looksLikeTokenQueryValue(query: String): Boolean =
        query.split('&').any { param ->
            param.substringBefore('=').lowercase() !in trackingParams &&
                tokenIn(param.substringAfter('=', "")) != null
        }

    /** The long token inside [text] when it contains one with both a letter and a digit. */
    private fun tokenIn(text: String): String? =
        longToken
            .findAll(text)
            .firstOrNull { match -> match.value.any { it.isDigit() } && match.value.any { it.isLetter() } }
            ?.value
}
