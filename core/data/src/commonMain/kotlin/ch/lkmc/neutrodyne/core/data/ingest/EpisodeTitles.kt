// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.ingest

import ch.lkmc.neutrodyne.feeds.model.ParsedEpisode
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The stored/displayed episode title of 03 Accepted items: the parsed title, else the UTC
 * pubDate day, else the percent-decoded enclosure file name, else `…`. The add-sheet preview
 * shares it so a shown title equals the one ingest stores.
 */
internal fun resolvedTitle(e: ParsedEpisode): String =
    e.title?.takeUnless { it.isBlank() }
        ?: e.pubDate?.let { utcDayText(it) }
        ?: e.primaryEnclosure?.url?.let(::enclosureFileName)
        ?: "…"

private fun utcDayText(ms: Long): String =
    kotlin.time.Instant
        .fromEpochMilliseconds(ms)
        .toLocalDateTime(TimeZone.UTC)
        .date
        .toString()

/** The last path segment of an enclosure URL, percent-decoded (03 title fallback). */
private fun enclosureFileName(url: String): String? {
    val path = url.substringBefore('?').substringBefore('#')
    val segment = path.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: return null
    val decoded = runCatching { percentDecode(segment) }.getOrNull() ?: return null
    return decoded.takeIf { it.isNotBlank() }
}

private fun percentDecode(s: String): String {
    val bytes = ByteArray(s.length)
    var out = 0
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 <= s.length - 1) {
            val hi = s[i + 1].hexDigit() ?: return s
            val lo = s[i + 2].hexDigit() ?: return s
            bytes[out++] = (hi * 16 + lo).toByte()
            i += 3
        } else {
            bytes[out++] = c.code.toByte()
            i++
        }
    }
    return bytes.copyOf(out).decodeToString()
}

private fun Char.hexDigit(): Int? =
    when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        in 'A'..'F' -> this - 'A' + 10
        else -> null
    }
