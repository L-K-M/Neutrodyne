// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.parse

/**
 * `itunes:duration` / `media:content@duration` parsing (03 Durations). The value is a hint; 06's measured
 * duration supersedes it. 1–3 numeric parts (`H:MM:SS`, `MM:SS`, `M:SS`, plain seconds), fractional last
 * part allowed; negative values, values over 48 h, empty and garbage yield null plus a `BAD_DURATION`
 * warning at the call site.
 */
public object Durations {
    private const val MAX_PARTS = 3
    private const val MS_PER_SECOND = 1_000.0
    private const val MS_PER_MINUTE = 60 * MS_PER_SECOND
    private const val MS_PER_HOUR = 60 * MS_PER_MINUTE

    /** Durations over 48 h are treated as garbage (03 Durations). */
    public const val MAX_VALUE_MS: Long = 48L * 60 * 60 * 1000

    private val numericPart = Regex("""\d+(?:\.\d+)?""")

    /** Parses a duration string to milliseconds, or null when it is not a valid duration. */
    public fun parseMs(raw: String): Long? {
        val parts = raw.trim().split(':')
        if (parts.size > MAX_PARTS) return null

        var totalMs = 0.0
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
            totalMs += part.toDouble() * multiplier
        }

        val ms = totalMs.toLong()
        if (ms > MAX_VALUE_MS) return null
        return ms
    }
}
