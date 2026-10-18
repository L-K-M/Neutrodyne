// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.opml

/**
 * `category` attribute decoding (05 walk rules): split on literal commas first, trim, keep the last
 * non-empty `/` segment of each token, then percent-decode exactly once in a single left-to-right
 * pass over only `%25` → `%`, `%2C` → `,` and `%2F` → `/` (hex case-insensitive). A decoded comma
 * never re-splits, a decoded slash never re-segments, `%25` never decodes twice (`a%252Cb` →
 * `a%2Cb`, never `a,b`), `+` is never a space and a `%` not followed by two hex digits stays
 * literal. Shared by every cascade pass and the import classifier's group mapping.
 */
public object OpmlCategories {
    public fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull(::lastSegment)
            .map(::decodeOnce)
    }

    /** The last non-empty `/` segment: `/Harvard/Berkman` → `Berkman`, `a//` → `a`, `///` → null. */
    private fun lastSegment(token: String): String? {
        if ('/' !in token) return token
        return token.split('/').lastOrNull { it.isNotEmpty() }
    }

    /**
     * One decoding pass: the cursor advances past a decoded triplet, so its output bytes are never
     * re-examined — `%252C` yields `%2C` and stops there.
     */
    private fun decodeOnce(token: String): String {
        if ('%' !in token) return token
        val out = StringBuilder(token.length)
        var i = 0
        while (i < token.length) {
            val c = token[i]
            if (c == '%' && i + 2 < token.length) {
                val hi = token[i + 1].digitToIntOrNull(16)
                val lo = token[i + 2].digitToIntOrNull(16)
                if (hi != null && lo != null) {
                    when (hi * 16 + lo) {
                        PERCENT, COMMA, SLASH -> {
                            out.append((hi * 16 + lo).toChar())
                            i += 3
                            continue
                        }
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private const val PERCENT = 0x25
    private const val COMMA = 0x2C
    private const val SLASH = 0x2F
}
