// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.html

import ch.lkmc.neutrodyne.feeds.html.ShowNotesStyles.NONE

/**
 * Turns timestamps in show-notes text into [NoteSpan.Timestamp] spans (03 Timestamp linkifier). Applied
 * to text outside links while the sanitiser walks the DOM; the renderer shows spans beyond a known
 * episode duration as plain text.
 *
 * Grammar (03): a negative lookbehind for digit/colon/dot, then `H:MM:SS` or `M:SS` (minutes up to
 * 999, long episodes often write "75:12"; seconds must be two digits, so ratios such as "16:9" never
 * match), then a lookahead pair excluding a following digit/colon and times of day ("10:30 am",
 * "20:15 Uhr", "10:30h") — the time-of-day check tolerates any whitespace run, including the
 * non-breaking and narrow no-break spaces of typeset text.
 */
public object TimestampLinkifier {
    private val lookbehind = """(?<![\d:.])"""
    private val clock = """(?:(\d{1,2}):([0-5]\d):([0-5]\d)|(\d{1,3}):([0-5]\d))"""
    private val lookahead = """(?![\d:])(?![\s\h]*(?i:am|pm|a\.m\.|p\.m\.|uhr|h\b))"""
    private val timestamp = Regex(lookbehind + clock + lookahead)

    private const val MS_PER_SECOND = 1000L
    private const val MS_PER_MINUTE = 60 * MS_PER_SECOND
    private const val MS_PER_HOUR = 60 * MS_PER_MINUTE

    /**
     * Splits [text] into [NoteSpan.Text] and [NoteSpan.Timestamp] spans; [style] carries onto the text
     * spans so the caller's emphasis survives. An empty input yields an empty list.
     */
    public fun linkify(
        text: String,
        style: Int = NONE,
    ): List<NoteSpan> {
        if (text.isEmpty()) return emptyList()

        val spans = mutableListOf<NoteSpan>()
        var cursor = 0
        for (match in timestamp.findAll(text)) {
            if (match.range.first > cursor) {
                spans.add(NoteSpan.Text(text.substring(cursor, match.range.first), style))
            }
            spans.add(NoteSpan.Timestamp(match.value, positionMs(match)))
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            spans.add(NoteSpan.Text(text.substring(cursor), style))
        }
        return spans
    }

    private fun positionMs(match: MatchResult): Long {
        val groups = match.groupValues
        return when {
            groups[1].isNotEmpty() -> {
                (
                    groups[1].toLong() * MS_PER_HOUR +
                        groups[2].toLong() * MS_PER_MINUTE +
                        groups[3].toLong() * MS_PER_SECOND
                )
            }

            groups[4].isNotEmpty() -> {
                groups[4].toLong() * MS_PER_MINUTE + groups[5].toLong() * MS_PER_SECOND
            }

            else -> {
                0L
            }
        }
    }
}
