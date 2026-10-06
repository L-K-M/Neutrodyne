// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

/**
 * Log redaction (01 Logging and redaction). Feed URLs commonly embed credentials and tracking
 * tokens (`https://user:pass@host/rss/a8F3kq09ZpLm2xQ?token=abc`), so every log message passes
 * through [text] before it reaches a sink, and code that holds a URL calls `url.redacted()`.
 *
 * Rules (from the design, matched by the JDK URI parser's behaviour where applicable):
 *
 * 1. Unparsable input logs as `<unparsable url, N chars>` — never the raw string.
 * 2. Anything before the last `@` in an authority is user-info → replaced by `***@`.
 * 3. Scheme, host and port are kept verbatim — `http`/`https`, IPv6 `[…]` literals, ports survive.
 * 4. Each non-empty path segment is kept when shorter than 15 chars or digit-free; longer
 *    digit-bearing (identifier-looking) segments become `…` + their last two chars:
 *    `/rss/a8F3kq09ZpLm2xQ` → `/rss/…xQ`, while `/feed/podcast` survives.
 * 5. Query parameter names are kept, every value becomes `…` (`token=abc` → `token=…`).
 * 6. The fragment is dropped, `#` included.
 * 7. In free text, `scheme:` runs with scheme ∈ {http, https, feed, pcast, podcast, itpc} are
 *    redacted; the feed-family schemes wrap an inner http(s) URL, which recurses.
 * 8. Sensitive header values in free text (`Authorization: Basic abc`, `Cookie: a=b`) are masked
 *    wholesale — "never log `Authorization`/`Cookie` values, credentials, tokens or API keys".
 * 9. Idempotent: `url(url(x)) == url(x)`.
 */
object Redactor {
    private const val MASKED_USER_INFO = "***@"
    private const val QUERY_VALUE_MASK = "…"
    private const val SEGMENT_MASK_PREFIX = "…"
    private const val HEADER_MASK = "…"
    private const val SEGMENT_KEEP_TAIL = 2
    private const val SEGMENT_MIN_LENGTH_FOR_MASKING = 15
    private const val UNPARSABLE_PREFIX = "<unparsable url, "
    private const val UNPARSABLE_SUFFIX = " chars>"

    /** Schemes a URL run inside free text may start with — anything else is left alone. */
    private val TEXT_SCHEMES = setOf("http", "https", "feed", "pcast", "podcast", "itpc")

    /** RFC 3986 scheme, anchored at the start of the string. */
    private val SCHEME = Regex("""^([A-Za-z][A-Za-z0-9+.-]*):""")

    /**
     * A `scheme:run` inside free text — stops at whitespace and obvious delimiters. One bracketed
     * IPv6 literal is part of the run *wherever* it sits in the authority
     * (`https://alice:pass@[2001:db8::1]/rss?token=…`, `feed:https://u:p@[::1]/f?key=…`); without it
     * the brackets end the match at the user-info and leave path and query unredacted (review
     * 2026-10-06).
     */
    private val URL_IN_TEXT =
        Regex(
            """[A-Za-z][A-Za-z0-9+.-]*:[^\s"'<>\[\]{}|\\^`]*(?:\[[^\s\]]+\])?[^\s"'<>\[\]{}|\\^`]*""",
        )

    /** Sentence punctuation that clings to a URL at the end of free text ("…see https://a/b.") */
    private val TRAILING_PUNCT = charArrayOf('.', ',', ';', ':', '!', '?', '\'', '"')

    /**
     * `Name: value` for headers that carry credentials; the value runs to end-of-line because a
     * token boundary is not reliably knowable ("Basic abc==", "a=b; c=d"). Applied after URL
     * redaction so a URL-valued credential is masked whole, not just inside-out.
     */
    private val SENSITIVE_HEADER =
        Regex(
            """(?i)\b(authorization|proxy-authorization|proxy-authenticate|x-api-key|x-auth-token|cookie|set-cookie)\s*:[^\r\n]*""",
        )

    /** Redacts a string that is already known to be a URL. Idempotent. */
    fun url(raw: String): String {
        // An earlier pass's marker (or any non-URL placeholder) must not nest inside a new marker.
        if (raw.startsWith(UNPARSABLE_PREFIX)) return raw
        val schemeMatch = SCHEME.find(raw) ?: return unparsable(raw)
        val scheme = schemeMatch.groupValues[1]
        val rest = raw.substring(schemeMatch.range.last + 1)
        if (rest.isEmpty()) return unparsable(raw)

        if (rest.startsWith("//")) return redactHierarchical(scheme, rest.substring(2))
        if (startsHierarchicalUrl(rest)) return "$scheme:${url(rest)}"
        return "$scheme:${redactTail(rest)}"
    }

    /** Redacts every URL run and sensitive-header value inside free text. */
    fun text(s: String): String {
        val urlsRedacted =
            URL_IN_TEXT.replace(s) { match ->
                val candidate = match.value
                val scheme = candidate.substring(0, candidate.indexOf(':')).lowercase()
                if (scheme !in TEXT_SCHEMES) return@replace candidate

                var trimmed = candidate.trimEnd(*TRAILING_PUNCT)
                // An unmatched ')' is a prose paren, not part of the URL ("(see https://a/f)").
                while (trimmed.endsWith(')') &&
                    trimmed.count { it == '(' } < trimmed.count { it == ')' }
                ) {
                    trimmed = trimmed.dropLast(1)
                }
                url(trimmed) + candidate.substring(trimmed.length)
            }
        return SENSITIVE_HEADER.replace(urlsRedacted) { m -> "${m.groupValues[1]}: $HEADER_MASK" }
    }

    private fun startsHierarchicalUrl(rest: String): Boolean {
        val inner = SCHEME.find(rest) ?: return false
        return rest.startsWith("//", startIndex = inner.range.last + 1)
    }

    /** `authority/path?query#fragment` — the part after `scheme://`. */
    private fun redactHierarchical(
        scheme: String,
        hier: String,
    ): String {
        val authorityEnd =
            hier
                .indexOfFirst { it == '/' || it == '?' || it == '#' }
                .let { if (it < 0) hier.length else it }
        val authority = hier.substring(0, authorityEnd)
        if (authority.isEmpty()) return unparsable("$scheme://$hier")

        val at = authority.lastIndexOf('@')
        val redactedAuthority = (if (at >= 0) MASKED_USER_INFO else "") + authority.substring(at + 1)

        return "$scheme://$redactedAuthority${redactTail(hier.substring(authorityEnd))}"
    }

    /** `path?query#fragment` masking shared by the hierarchical and opaque forms. */
    private fun redactTail(tail: String): String {
        val beforeFragment = tail.substringBefore('#')
        val path = beforeFragment.substringBefore('?')
        val hasQuery = '?' in beforeFragment
        val query = if (hasQuery) beforeFragment.substringAfter('?') else ""

        val maskedPath =
            path.split('/').joinToString("/") { segment ->
                if (segment.isEmpty() || segment.length < SEGMENT_MIN_LENGTH_FOR_MASKING ||
                    segment.none { it.isDigit() }
                ) {
                    segment
                } else {
                    SEGMENT_MASK_PREFIX + segment.takeLast(SEGMENT_KEEP_TAIL)
                }
            }
        if (!hasQuery) return maskedPath

        val maskedQuery =
            query.split('&').joinToString("&") { part ->
                val eq = part.indexOf('=')
                if (eq < 0) part else part.substring(0, eq + 1) + QUERY_VALUE_MASK
            }
        return "$maskedPath?$maskedQuery"
    }

    private fun unparsable(raw: String) = "$UNPARSABLE_PREFIX${raw.length}$UNPARSABLE_SUFFIX"
}
