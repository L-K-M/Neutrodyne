// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.fetch

import io.ktor.http.HttpHeaders

/**
 * Header parsing for the fetch pipeline (03 Validators and Response handling):
 * `max-age` out of `Cache-Control`, `Date` as epoch ms (IMF-fixdate), `Retry-After`.
 */
internal object FeedHttpHeaders {
    /** `Cache-Control: max-age=<seconds>`; null when absent or not a number. */
    fun maxAgeSec(cacheControl: String?): Long? {
        if (cacheControl == null) return null
        for (part in cacheControl.split(',')) {
            val directive = part.trim()
            // The directive name must be `max-age` itself — extensions like `max-age-x` are not it.
            if (!directive.substringBefore('=').trim().equals("max-age", ignoreCase = true)) continue
            return directive.substringAfter('=', "").trim().toLongOrNull()
        }
        return null
    }

    /**
     * `Date: Tue, 15 Nov 1994 08:12:31 GMT` → epoch ms; null when absent or unparsable.
     * IMF-fixdate only (the RFC 7231-required form); RFC 850/asctime dates fall back to null.
     */
    fun serverDateMs(date: String?): Long? {
        if (date == null) return null
        val m = DATE_RE.matchEntire(date.trim()) ?: return null
        val day = m.groupValues[1].toIntOrNull() ?: return null
        val month = MONTHS.indexOf(m.groupValues[2]) + 1
        if (month == 0) return null
        val year = m.groupValues[3].toIntOrNull() ?: return null
        val h = m.groupValues[4].toIntOrNull() ?: return null
        val min = m.groupValues[5].toIntOrNull() ?: return null
        val s = m.groupValues[6].toIntOrNull() ?: return null
        if (day !in 1..31 || h > 23 || min > 59 || s > 60) return null
        return toEpochMs(year, month, day, h, min, s)
    }

    /**
     * `Retry-After`: delta-seconds, or an IMF-fixdate relative to [nowMs] (03 Response handling);
     * non-positive or past values clamp to 0, absurdly large deltas saturate instead of wrapping.
     */
    fun retryAfterMs(
        value: String?,
        nowMs: Long,
    ): Long? {
        val trimmed = value?.trim() ?: return null
        trimmed.toLongOrNull()?.let { sec ->
            if (sec <= 0) return 0L
            return if (sec >= Long.MAX_VALUE / 1000) Long.MAX_VALUE else sec * 1000
        }
        return serverDateMs(trimmed)?.let { (it - nowMs).coerceAtLeast(0L) }
    }

    /**
     * `WWW-Authenticate: Basic realm="…"` → (isBasic, realm). Servers may send the header more
     * than once or pack several challenges into one value — a `Basic` challenge is found anywhere
     * in the list (e.g. `Digest realm=…, Basic realm="x"`), not just when the value leads with it.
     */
    fun basicChallenge(headers: List<String>?): Pair<Boolean, String?> {
        if (headers == null) return false to null
        for (header in headers) {
            basicChallengeIn(header)?.let { return it }
        }
        return false to null
    }

    fun contentTypeCharset(contentType: String?): String? {
        if (contentType == null) return null
        return contentType
            .split(';')
            .drop(1)
            .map { it.trim() }
            .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim('"', ' ')
            ?.takeIf { it.isNotEmpty() }
    }

    private val DATE_RE =
        Regex("""[A-Za-z]{3},\s*(\d{1,2})\s*([A-Za-z]{3})\s*(\d{4})\s*(\d{2}):(\d{2}):(\d{2})\s*GMT""")

    /**
     * The Basic verdict of one `WWW-Authenticate` value, or null when it carries no `Basic`
     * challenge. An iterative walk of the RFC 9110 §11.6.1 challenge list —
     * `auth-scheme [ 1*SP ( token68 / auth-param *( OWS "," OWS auth-param ) ) ]` — so a
     * server-controlled param count stays linear; the recursive regex it replaces overflowed
     * the JVM stack around a thousand params and cost the response its HTTP outcome. At each
     * top-level comma the lookahead decides: `, token =` continues the current challenge's
     * auth-params, anything else opens a new challenge. The first `Basic` challenge carrying
     * a blob decides the realm; a bare `Basic` counts only when the value has no parametrized
     * `Basic` at all.
     */
    private fun basicChallengeIn(header: String): Pair<Boolean, String?>? {
        val n = minOf(header.length, MAX_CHALLENGE_CHARS)
        var i = 0
        var bare = false
        while (true) {
            while (i < n && (header[i] == ',' || header[i].isOws())) i++
            if (i >= n) break
            val start = i
            while (i < n && header[i].isTokenChar()) i++
            if (i == start) {
                // Not a token start — malformed input; step over the byte and keep scanning.
                i++
                continue
            }
            val scheme = header.substring(start, i)
            val owsEnd = i
            while (i < n && header[i].isOws()) i++
            val hasBlob = i > owsEnd
            when {
                i < n && header[i] == '=' -> {
                    // `name=value` in scheme position — an auth-param the challenge
                    // boundary swallowed; skip its value and keep looking for a scheme.
                    i = skipAuthParamValue(header, i + 1, n)
                }

                !scheme.equals(BASIC_SCHEME, ignoreCase = true) -> {
                    // Another scheme's params may contain `Basic` as a param name or inside a
                    // quoted value — skip the blob so only real schemes are inspected.
                    if (hasBlob) i = skipChallengeBlob(header, i, n)
                }

                hasBlob && (i >= n || header[i] == ',') -> {
                    bare = true
                }

                !hasBlob -> {
                    // `Basic` before a comma or the end is the bare form; glued to any other
                    // byte it is junk to step over, not a challenge.
                    if (i < n && header[i] != ',') {
                        i = skipChallengeBlob(header, i, n)
                    } else {
                        bare = true
                    }
                }

                else -> {
                    return true to basicRealm(header, i, n)
                }
            }
        }
        return if (bare) true to null else null
    }

    /**
     * Skips one challenge's blob — a token68 or an auth-param list — from [start]; returns the
     * index of the comma that starts the next challenge, or [n]. Commas inside quoted strings
     * never delimit.
     */
    private fun skipChallengeBlob(
        header: String,
        start: Int,
        n: Int,
    ): Int {
        var i = start
        while (i < n) {
            while (i < n && header[i].isOws()) i++
            if (i >= n) break
            if (header[i] == ',') {
                // `, token =` continues the auth-param list; anything else is a new challenge.
                var j = i + 1
                while (j < n && header[j].isOws()) j++
                val token = j
                while (j < n && header[j].isTokenChar()) j++
                while (j < n && header[j].isOws()) j++
                if (j > token && j < n && header[j] == '=') {
                    i++
                    continue
                }
                return i
            }
            val element = i
            if (header[i] == '"') {
                i = skipQuoted(header, i, n)
            } else {
                while (i < n && header[i].isTokenChar()) i++
                var k = i
                while (k < n && header[k].isOws()) k++
                if (k < n && header[k] == '=') {
                    i = skipAuthParamValue(header, k + 1, n)
                }
            }
            if (i == element) i++
        }
        return i
    }

    /** Skips an auth-param's value; [start] sits just past its `=`. */
    private fun skipAuthParamValue(
        header: String,
        start: Int,
        n: Int,
    ): Int {
        var i = start
        while (i < n && header[i].isOws()) i++
        if (i < n && header[i] == '"') return skipQuoted(header, i, n)
        while (i < n && header[i].isTokenChar()) i++
        return i
    }

    /** Skips a `"`-quoted string, honouring backslash escapes; unterminated consumes to [n]. */
    private fun skipQuoted(
        header: String,
        start: Int,
        n: Int,
    ): Int {
        var i = start + 1
        while (i < n) {
            when (header[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i++
            }
        }
        return n
    }

    /**
     * The quoted `realm=` value of this `Basic` challenge's auth-param list (RFC 7617), or
     * null when the blob is a token68, has no realm or quotes one without a closing quote.
     * Params after the realm still parse, but the first quoted realm wins.
     */
    private fun basicRealm(
        header: String,
        start: Int,
        n: Int,
    ): String? {
        var i = start
        var realm: String? = null
        while (i < n) {
            while (i < n && header[i].isOws()) i++
            if (i >= n || header[i] == ',') break
            val nameStart = i
            while (i < n && header[i].isTokenChar()) i++
            if (i == nameStart) break
            val name = header.substring(nameStart, i)
            while (i < n && header[i].isOws()) i++
            // No `=`: the blob is a token68 or the next challenge — the param list is over.
            if (i >= n || header[i] != '=') break
            i++
            while (i < n && header[i].isOws()) i++
            val value: String?
            val quoted = i < n && header[i] == '"'
            if (quoted) {
                val (text, end) = readQuoted(header, i, n)
                value = text
                i = end
            } else {
                val valueStart = i
                while (i < n && header[i].isTokenChar()) i++
                value = header.substring(valueStart, i)
            }
            if (realm == null && quoted && name.equals(REALM_PARAM, ignoreCase = true)) {
                realm = value
            }
            while (i < n && header[i].isOws()) i++
            if (i >= n || header[i] != ',') break
            i++
        }
        return realm
    }

    /**
     * A `"`-quoted string with backslash escapes (the escaped byte itself is kept); null when
     * the closing quote is missing. The int is the index just past the string.
     */
    private fun readQuoted(
        header: String,
        start: Int,
        n: Int,
    ): Pair<String?, Int> {
        val out = StringBuilder()
        var i = start + 1
        while (i < n) {
            when (header[i]) {
                '\\' -> {
                    if (i + 1 < n) out.append(header[i + 1])
                    i += 2
                }

                '"' -> {
                    return out.toString() to i + 1
                }

                else -> {
                    out.append(header[i])
                    i++
                }
            }
        }
        return null to n
    }

    /** SP or HTAB — the OWS/BWS of the challenge grammar. */
    private fun Char.isOws(): Boolean = this == ' ' || this == '\t'

    /** RFC 9110 `tchar`. */
    private fun Char.isTokenChar(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' ||
            this == '!' || this == '#' || this == '$' || this == '%' || this == '&' ||
            this == '\'' || this == '*' || this == '+' || this == '-' || this == '.' ||
            this == '^' || this == '_' || this == '`' || this == '|' || this == '~'

    private const val BASIC_SCHEME = "Basic"
    private const val REALM_PARAM = "realm"

    /**
     * Defensive bound on one header value's scan — past OkHttp's 256 KiB per-line cap no
     * intact value arrives anyway; the tokenizer itself is linear, this only fences off
     * pathological in-memory strings.
     */
    private const val MAX_CHALLENGE_CHARS = 256 * 1024

    private val MONTHS =
        listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** UTC epoch milliseconds; [month] is 1-based. Proleptic Gregorian calendar. */
    private fun toEpochMs(
        year: Int,
        month: Int,
        day: Int,
        h: Int,
        min: Int,
        s: Int,
    ): Long {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val era = y.floorDiv(400)
        val yoe = y - era * 400
        val mp = m - 3
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146097L + doe - 719468L
        return (days * 86400L + h * 3600L + min * 60L + s) * 1000L
    }

    /**
     * RFC 3986 §5.2.2 reference resolution for `Location` against the current hop: absolute,
     * network-path (`//host`), absolute-path and relative-path references, `?query`-only and
     * `#fragment` forms, with dot-segment removal (03 Request rules). A reference resolving back
     * to [base] (e.g. `#section` on the request URL) is unusable — the chain cannot follow it
     * without re-requesting the same URL.
     */
    fun resolveLocation(
        base: String,
        location: String,
    ): String? {
        val loc = location.trim()
        if (loc.isEmpty()) return null
        val b = parseRef(base) ?: return null
        val r = parseRef(loc) ?: return null
        if (r.authority != null && r.authority.isEmpty()) return null

        val tScheme: String?
        val tAuthority: String?
        val tPath: String
        val tQuery: String?
        when {
            r.scheme != null -> {
                tScheme = r.scheme
                tAuthority = r.authority
                tPath = removeDotSegments(r.path)
                tQuery = r.query
            }

            r.authority != null -> {
                tScheme = b.scheme
                tAuthority = r.authority
                tPath = removeDotSegments(r.path)
                tQuery = r.query
            }

            r.path.isEmpty() -> {
                tScheme = b.scheme
                tAuthority = b.authority
                tPath = b.path
                tQuery = r.query ?: b.query
            }

            r.path.startsWith("/") -> {
                tScheme = b.scheme
                tAuthority = b.authority
                tPath = removeDotSegments(r.path)
                tQuery = r.query
            }

            else -> {
                tScheme = b.scheme
                tAuthority = b.authority
                tPath = removeDotSegments(mergePaths(b, r.path))
                tQuery = r.query
            }
        }

        val resolved =
            buildString {
                // RFC 3986 schemes are case-insensitive; emit the canonical lowercase so the
                // caller's http(s) check and the hop-tracking sets see a normal form.
                tScheme?.let { append(it.lowercase()).append(':') }
                tAuthority?.let { append("//").append(it) }
                append(tPath)
                tQuery?.let { append('?').append(it) }
            }
        return resolved.takeUnless { it.isEmpty() || it == base }
    }

    /** A parsed URI reference; a null component is *absent* (an empty query still overrides). */
    private class UriRef(
        val scheme: String?,
        val authority: String?,
        val path: String,
        val query: String?,
    )

    /** Splits `scheme://authority/path?query#fragment` leniently; the fragment is dropped. */
    private fun parseRef(ref: String): UriRef? {
        var rest = ref.substringBefore('#')
        val scheme =
            SCHEME_RE.find(rest)?.let {
                rest = rest.substring(it.value.length + 1)
                it.value
            }
        val authority =
            if (rest.startsWith("//")) {
                rest.substring(2).substringBefore('/').substringBefore('?').also {
                    rest = rest.substring(2 + it.length)
                }
            } else {
                null
            }
        val query = if ('?' in rest) rest.substringAfter('?') else null
        val path = rest.substringBefore('?')
        if (scheme == null && authority == null && path.isEmpty() && query == null) return null
        return UriRef(scheme, authority, path, query)
    }

    /** RFC 3986 §5.2.3: the reference's path merged onto the base's directory. */
    private fun mergePaths(
        base: UriRef,
        refPath: String,
    ): String =
        if (base.authority != null && base.path.isEmpty()) {
            "/$refPath"
        } else {
            base.path.substringBeforeLast('/', "") + "/" + refPath
        }

    /** RFC 3986 §5.2.4 `remove_dot_segments`. */
    private fun removeDotSegments(path: String): String {
        val out = StringBuilder(path.length)
        var input = path
        while (input.isNotEmpty()) {
            when {
                input.startsWith("../") -> {
                    input = input.substring(3)
                }

                input.startsWith("./") -> {
                    input = input.substring(2)
                }

                input.startsWith("/./") -> {
                    input = "/" + input.substring(3)
                }

                input == "/." -> {
                    input = "/"
                }

                input.startsWith("/../") -> {
                    input = "/" + input.substring(4)
                    out.dropLastSegment()
                }

                input == "/.." -> {
                    input = "/"
                    out.dropLastSegment()
                }

                input == "." || input == ".." -> {
                    input = ""
                }

                else -> {
                    val end =
                        if (input.startsWith("/")) {
                            input.indexOf('/', 1).let { if (it < 0) input.length else it }
                        } else {
                            input.indexOf('/').let { if (it < 0) input.length else it }
                        }
                    out.append(input, 0, end)
                    input = input.substring(end)
                }
            }
        }
        return out.toString()
    }

    private fun StringBuilder.dropLastSegment() {
        val lastSlash = lastIndexOf('/')
        if (lastSlash >= 0) setLength(lastSlash) else setLength(0)
    }

    private val SCHEME_RE = Regex("""^[A-Za-z][A-Za-z0-9+.-]*(?=:)""")

    /** Header names used across the pipeline. */
    val CACHE_CONTROL = HttpHeaders.CacheControl
    val DATE = HttpHeaders.Date
    val RETRY_AFTER = HttpHeaders.RetryAfter
    val WWW_AUTHENTICATE = HttpHeaders.WWWAuthenticate
    val ETAG = HttpHeaders.ETag
    val LAST_MODIFIED = HttpHeaders.LastModified
    val LOCATION = HttpHeaders.Location
    val CONTENT_TYPE = HttpHeaders.ContentType
}
