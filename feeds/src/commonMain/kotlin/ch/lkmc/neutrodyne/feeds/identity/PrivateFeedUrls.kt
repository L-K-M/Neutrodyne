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

    /** Whether [url] looks like a private (token-carrying) feed URL. */
    public fun looksPrivate(url: String): Boolean {
        val parts = splitLenient(url)

        // Credentials in the userinfo: the strongest signal.
        if (parts.userInfo != null) return true

        val host = parts.host?.lowercase() ?: return false
        if (privateHosts.any { host == it || host.endsWith(".$it") }) return true

        // Token-bearing query parameter names, then long tokens in any query value.
        val query = parts.query ?: ""
        if (query.split('&').any { param ->
                val name = param.substringBefore('=').lowercase()
                name in tokenParameterNames
            }
        ) {
            return true
        }
        if (looksLikeTokenQueryValue(query)) return true

        // Long tokens as part of any path segment ("/feeds/xKd93lskSKEa1zl/"). The permissive
        // class deliberately keeps '-'/'_': UUID and base64url tokens carry them, and a false
        // positive on a hyphenated slug only costs a warning.
        return parts.path.split('/').any { segment -> tokenIn(segment) != null }
    }

    private fun looksLikeTokenQueryValue(query: String): Boolean =
        query.split('&').map { it.substringAfter('=', "") }.any { value -> tokenIn(value) != null }

    /** The long token inside [text] when it contains one with both a letter and a digit. */
    private fun tokenIn(text: String): String? =
        longToken
            .findAll(text)
            .firstOrNull { match -> match.value.any { it.isDigit() } && match.value.any { it.isLetter() } }
            ?.value
}
