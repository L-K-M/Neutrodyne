// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.api

/** One validated description chapter; the repository supplies next-start/duration end bounds. */
data class ChapterSpec(
    val startMs: Long,
    val title: String,
)

/** Pure, fail-closed description parsing. Callers must first establish description completeness. */
object YouTubeChapters {
    // Token grammar and clock-marker word boundaries mirror 03's TimestampLinkifier. This API
    // cannot depend on feeds; the description-line separator is an additional 04 rule.
    private val clock = Regex("""(?:([0-9]{1,2}):([0-5][0-9]):([0-5][0-9])|([0-9]{1,3}):([0-5][0-9]))""")
    private val numericStart = Regex("""^[+\-−]?\p{N}""")
    private val clockMarker =
        Regex(
            """^[ \t\u00A0\u1680\u180E\u2000-\u200A\u202F\u205F\u3000]*(?i:(?:am|pm|uhr|h)(?![\p{L}\p{N}_])|a\.m\.?(?![\p{L}\p{N}_])|p\.m\.?(?![\p{L}\p{N}_]))""",
        )
    private const val SEPARATORS = "-–—|:"
    private const val MAX_DESCRIPTION_BYTES = 2 * 1024 * 1024
    private const val MAX_CHAPTERS = 1_000
    private const val MIN_INTERVAL_MS = 10_000L

    /** Oversized input/lists and malformed timestamp-led lines reject the whole list. */
    fun parse(
        description: String,
        durationMs: Long?,
    ): List<ChapterSpec> {
        if (description.length > MAX_DESCRIPTION_BYTES ||
            description.encodeToByteArray().size > MAX_DESCRIPTION_BYTES ||
            (durationMs != null && durationMs <= 0)
        ) {
            return emptyList()
        }
        val chapters = mutableListOf<ChapterSpec>()
        for (raw in description.lineSequence()) {
            val line = raw.trim { it.isChapterSpace() }
            if (line.isEmpty()) continue
            if (!numericStart.containsMatchIn(line)) continue
            val tokenEnd = line.indexOfFirst { it.isChapterSpace() }.takeIf { it >= 0 } ?: line.length
            if (line.indexOf(':') !in 0 until tokenEnd) continue
            val chapter = parseLine(line) ?: return emptyList()
            if (chapters.isEmpty()) {
                if (chapter.startMs != 0L) return emptyList()
            } else if (chapter.startMs - chapters.last().startMs < MIN_INTERVAL_MS) {
                return emptyList()
            }
            if (chapters.size == MAX_CHAPTERS) return emptyList()
            chapters += chapter
        }
        if (chapters.size < 3) return emptyList()
        if (durationMs != null && durationMs - chapters.last().startMs < MIN_INTERVAL_MS) return emptyList()
        return chapters.toList()
    }

    private fun parseLine(line: String): ChapterSpec? {
        // Scan the entire numeric clock before matching, so a malformed H:MM:SS cannot fall
        // back to M:SS plus a colon/title and authorize a plausible prefix.
        var end = 0
        while (end < line.length && (line[end] in '0'..'9' || line[end] == ':')) end++
        val numeric = line.substring(0, end)
        val colonSeparator = numeric.endsWith(':')
        val match = clock.matchEntire(if (colonSeparator) numeric.dropLast(1) else numeric) ?: return null
        val tail = line.substring(end)
        if (!colonSeparator && (tail.isEmpty() || (!tail.first().isChapterSpace() && tail.first() !in SEPARATORS))) {
            return null
        }
        if (!colonSeparator && clockMarker.containsMatchIn(tail)) return null
        var title = tail.trim { it.isChapterSpace() }
        if (!colonSeparator && title.firstOrNull()?.let { it in SEPARATORS } == true) {
            title = title.drop(1).trim { it.isChapterSpace() }
        }
        if (title.isEmpty()) return null
        val g = match.groupValues
        val seconds =
            if (g[1].isNotEmpty()) {
                g[1].toLong() * 3_600 + g[2].toLong() * 60 + g[3].toLong()
            } else {
                g[4].toLong() * 60 + g[5].toLong()
            }
        return ChapterSpec(seconds * 1_000, title)
    }

    private fun Char.isChapterSpace() = isWhitespace() || this == '\u180E'
}
