// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.parse

/**
 * `itunes:duration` / `media:content@duration` parsing (03 Durations). The value is a hint; 06's measured
 * duration supersedes it. 1–3 numeric parts (`H:MM:SS`, `MM:SS`, `M:SS`, plain seconds), fractional last
 * part allowed; negative values, values over 48 h, all-zero placeholders (`00:00`), empty and garbage
 * yield null plus a `BAD_DURATION` warning at the call site.
 */
public object Durations {
    private const val MAX_PARTS = 3
    private const val MS_PER_SECOND = 1_000L
    private const val MS_PER_MINUTE = 60L * MS_PER_SECOND
    private const val MS_PER_HOUR = 60L * MS_PER_MINUTE
    private const val FRACTION_PAD = "000"

    /** Durations over 48 h are treated as garbage (03 Durations). */
    public const val MAX_VALUE_MS: Long = 48L * 60 * 60 * 1000

    private val numericPart = Regex("""\d+(?:\.\d+)?""")

    /** Parses a duration string to milliseconds, or null when it is not a valid duration. */
    public fun parseMs(raw: String): Long? {
        val parts = raw.trim().split(':')
        if (parts.size > MAX_PARTS) return null

        // Integer arithmetic throughout: a fractional last part keeps whole milliseconds ("1.001").
        var totalMs = 0L
        for (i in parts.indices) {
            val part = parts[i]
            val isLast = i == parts.lastIndex
            if (!numericPart.matches(part)) return null
            if (!isLast && part.contains('.')) return null

            val multiplier =
                when (parts.size - i) {
                    1 -> MS_PER_SECOND
                    2 -> MS_PER_MINUTE
                    else -> MS_PER_HOUR
                }
            val whole = part.substringBefore('.')
            val wholeValue = whole.toLongOrNull() ?: return null
            if (wholeValue > MAX_VALUE_MS / multiplier) return null
            totalMs += wholeValue * multiplier

            if (isLast && part.contains('.')) {
                // The fraction is seconds-digits: pad to three places for milliseconds.
                val fraction = part.substringAfter('.')
                totalMs += ((fraction + FRACTION_PAD).substring(0, 3).toIntOrNull() ?: return null)
            }
        }

        if (totalMs > MAX_VALUE_MS) return null
        // "00:00"-style placeholders mean the feed carries no real duration — an unknown, not a
        // zero-length episode.
        if (totalMs == 0L) return null
        return totalMs
    }
}
