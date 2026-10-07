// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.fetch

import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom

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
            if (!directive.startsWith("max-age", ignoreCase = true)) continue
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
     * negative dates (already past) clamp to 0.
     */
    fun retryAfterMs(
        value: String?,
        nowMs: Long,
    ): Long? {
        val trimmed = value?.trim() ?: return null
        trimmed.toLongOrNull()?.let { return it * 1000L }
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
            val challenge = BASIC_CHALLENGE_RE.find(header)
            if (challenge != null) {
                return true to REALM_RE.find(challenge.groupValues[1])?.groupValues?.get(1)
            }
            if (BASIC_BARE_RE.containsMatchIn(header)) return true to null
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

    private val REALM_RE = Regex("""realm\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)

    /** A `Basic` challenge carrying auth-params anywhere in a challenge list. */
    private val BASIC_CHALLENGE_RE =
        Regex(
            """(?i)(?:^|,)\s*Basic\s+((?:[A-Za-z0-9_-]+\s*=\s*(?:"[^"]*"|[^,\s"]*)\s*,?\s*)+)""",
        )

    /** A bare `Basic` challenge (no params — permitted though useless), anywhere in the list. */
    private val BASIC_BARE_RE = Regex("""(?i)(?:^|,)\s*Basic\s*(?:,|$)""")

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
        val era = Math.floorDiv(y, 400)
        val yoe = y - era * 400
        val mp = m - 3
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146097L + doe - 719468L
        return (days * 86400L + h * 3600L + min * 60L + s) * 1000L
    }

    /**
     * RFC 3986 reference resolution for `Location` against the current hop — Ktor's
     * `URLBuilder.takeFrom` handles absolute, network-path, absolute/relative-path, query-only
     * and fragment references plus dot-segment removal (03 Request rules). A bare `#…` reference
     * (the document itself) resolves back to [base] and is reported as unusable: the redirect
     * chain cannot follow it without re-requesting the same URL.
     */
    fun resolveLocation(
        base: String,
        location: String,
    ): String? {
        val loc = location.trim()
        if (loc.isEmpty()) return null
        val resolved =
            runCatching { URLBuilder(base).takeFrom(loc).build().toString() }.getOrNull()
                ?: return null
        return resolved.substringBefore('#').takeUnless { it == base }
    }

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
