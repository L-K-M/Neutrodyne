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
 *    redacted; feed-family wrappers (`feed:podcast:https://…`) are peeled to the inner URL.
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

    /** RFC 3986 scheme characters besides ASCII letters and digits. */
    private const val SCHEME_PUNCT = "+-."

    /** The exact placeholder [unparsable] writes; anything else that merely looks like it is redacted. */
    private val UNPARSABLE_MARKER = Regex("""<unparsable url, \d+ chars>""")

    /** Characters that always end a URL run in free text: whitespace aside, RFC 3986 never allows them raw. */
    private const val RUN_DELIMITERS = "\"<>{}|\\^`"

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
        // An earlier pass's marker must not nest inside a new one; only the exact marker passes.
        if (UNPARSABLE_MARKER.matches(raw)) return raw

        // Walk the scheme chain by offsets (`feed:podcast:https://…`): linear and without recursion.
        // Feed-family wrappers are peeled only when the chain ends in a hierarchical `scheme://`;
        // otherwise all after the first scheme is one opaque part, so `https:http:feed:@h` keeps no
        // user-info (review 5, 2026-10-06).
        val firstColon = schemeColonAt(raw, 0)
        if (firstColon < 0) return unparsable(raw)
        var schemeStart = 0
        var colon = firstColon
        var wrappersKnown = true
        while (!raw.startsWith("//", colon + 1)) {
            val next = schemeColonAt(raw, colon + 1)
            if (next < 0) break
            wrappersKnown = wrappersKnown && raw.substring(schemeStart, colon).lowercase() in TEXT_SCHEMES
            schemeStart = colon + 1
            colon = next
        }

        if (wrappersKnown && raw.startsWith("//", colon + 1)) {
            val hierarchical =
                redactHierarchical(raw.substring(schemeStart, colon), raw.substring(colon + 3))
                    ?: return unparsable(raw)
            return raw.substring(0, schemeStart) + hierarchical
        }

        val opaque = redactOpaque(raw.substring(firstColon + 1))
        if (opaque.isEmpty()) return unparsable(raw)
        return raw.substring(0, firstColon + 1) + opaque
    }

    /**
     * Redacts every URL run and sensitive-header value inside free text. A single forward scan,
     * no regex over the runs: a run starts wherever a [TEXT_SCHEMES] scheme and its colon begin
     * (glued labels included: `Error:https://…`, `URL:[https://…]`) and ends at whitespace, a
     * [RUN_DELIMITERS] character, or where a joined URL starts ([startsJoinedUrl]). Reviews 3–5,
     * 2026-10-06.
     */
    fun text(s: String): String {
        val out = StringBuilder(s.length)
        var copied = 0
        var start = nextRunStart(s, 0)
        while (start >= 0) {
            val end = runEnd(s, start)
            out.append(s, copied, start).append(redactRun(s.substring(start, end)))
            copied = end
            start = nextRunStart(s, end)
        }
        out.append(s, copied, s.length)
        return SENSITIVE_HEADER.replace(out) { m -> "${m.groupValues[1]}: $HEADER_MASK" }
    }

    /** First index ≥ [from] where a run starts, or -1. */
    private fun nextRunStart(
        s: String,
        from: Int,
    ): Int {
        for (i in from until s.length) {
            if (runStartsAt(s, i)) return i
        }
        return -1
    }

    /** `scheme:` with a [TEXT_SCHEMES] scheme at [i], followed by at least one run character. */
    private fun runStartsAt(
        s: String,
        i: Int,
    ): Boolean {
        for (scheme in TEXT_SCHEMES) {
            val colon = i + scheme.length
            if (colon + 1 >= s.length || s[colon] != ':') continue
            if (!s.regionMatches(i, scheme, 0, scheme.length, ignoreCase = true)) continue
            if (isRunChar(s[colon + 1])) return true
        }
        return false
    }

    /**
     * End (exclusive) of the run starting at [start]. The authority (after `//`, up to the first
     * `/`, `?` or `#`) splits only at a join, so user-info that looks like a scheme
     * (`https://http:pass@h/`) and IPv6 literals with zone ids stay in one piece.
     */
    private fun runEnd(
        s: String,
        start: Int,
    ): Int {
        var i = s.indexOf(':', start) + 1
        var inAuthority = s.startsWith("//", i)
        if (inAuthority) i += 2
        while (i < s.length) {
            val c = s[i]
            if (!isRunChar(c) || startsJoinedUrl(s, i, inAuthority)) return i
            if (inAuthority && (c == '/' || c == '?' || c == '#')) inAuthority = false
            i++
        }
        return s.length
    }

    /**
     * Whether a second URL starts at [i] inside a run: a hierarchical `scheme://` right after a
     * `,` or `;` (joined URLs, also straight after an authority) or after a `/` outside the
     * authority (redirect prefixes: `https://op3.test/e/https://…`). Anywhere else (query values,
     * fragments, mid-segment, wrapper chains) the run stays whole and [url] redacts it as one.
     */
    private fun startsJoinedUrl(
        s: String,
        i: Int,
        inAuthority: Boolean,
    ): Boolean {
        val prev = s[i - 1]
        val joinable = prev == ',' || prev == ';' || (prev == '/' && !inAuthority)
        if (!joinable || !runStartsAt(s, i)) return false
        val colon = schemeColonAt(s, i)
        return colon >= 0 && s.startsWith("//", colon + 1)
    }

    /** Index of the colon ending an RFC 3986 scheme that starts at [from], or -1. */
    private fun schemeColonAt(
        s: String,
        from: Int,
    ): Int {
        if (from >= s.length || !s[from].isAsciiLetter()) return -1
        var i = from + 1
        while (i < s.length && (s[i].isAsciiLetter() || s[i] in '0'..'9' || s[i] in SCHEME_PUNCT)) i++
        return if (i < s.length && s[i] == ':') i else -1
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    private fun isRunChar(c: Char): Boolean = !c.isWhitespace() && c !in RUN_DELIMITERS

    /**
     * Redacts one run after trimming what prose glues to its end: sentence punctuation and an
     * unmatched `)` or `]` ("(see https://a/f).", "[https://a/f]"). A bare wrapper left by a split
     * (`feed:` before `https://…`) is kept as is.
     */
    private fun redactRun(run: String): String {
        val openParens = run.count { it == '(' }
        var closeParens = run.count { it == ')' }
        val openBrackets = run.count { it == '[' }
        var closeBrackets = run.count { it == ']' }
        // Never trim into `scheme:` itself, whose colon is also sentence punctuation.
        val schemeEnd = run.indexOf(':') + 1
        var end = run.length
        while (end > schemeEnd) {
            val c = run[end - 1]
            when {
                c in TRAILING_PUNCT -> {
                    end--
                }

                c == ')' && closeParens > openParens -> {
                    end--
                    closeParens--
                }

                c == ']' && closeBrackets > openBrackets -> {
                    end--
                    closeBrackets--
                }

                else -> {
                    break
                }
            }
        }
        val trimmed = run.substring(0, end)
        if (trimmed.length == schemeEnd) return run
        return url(trimmed) + run.substring(end)
    }

    /**
     * The part after an opaque `scheme:`. User-info-like text before an `@` in its first segment
     * is masked as in an authority (`https:alice:pass@h` → `https:***@h`); the rest is a tail.
     */
    private fun redactOpaque(rest: String): String {
        val firstSegmentEnd =
            rest
                .indexOfFirst { it == '/' || it == '?' || it == '#' }
                .let { if (it < 0) rest.length else it }
        val at = rest.lastIndexOf('@', firstSegmentEnd - 1)
        if (at < 0) return redactTail(rest)
        return MASKED_USER_INFO + redactTail(rest.substring(at + 1))
    }

    /** `authority/path?query#fragment`, the part after `scheme://`; null without an authority. */
    private fun redactHierarchical(
        scheme: String,
        hier: String,
    ): String? {
        val authorityEnd =
            hier
                .indexOfFirst { it == '/' || it == '?' || it == '#' }
                .let { if (it < 0) hier.length else it }
        val authority = hier.substring(0, authorityEnd)
        if (authority.isEmpty()) return null

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
