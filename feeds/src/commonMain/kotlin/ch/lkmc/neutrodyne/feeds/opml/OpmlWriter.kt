// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feeds.opml

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import okio.BufferedSink

/**
 * The deterministic OPML 2.0 writer shared by both apps and the sync server (05 OPML export, D31).
 * Hand-written string output because `commonMain` has no `XmlSerializer` and Android's throws on
 * characters outside XML 1.0; content never aborts an export. Output is byte-identical for equal input:
 * fixed attribute order, UTF-8, an English UTC `dateCreated` and a stable title sort.
 */
public object OpmlWriter {
    /** Indentation per nesting level; index = level, so feeds sit one level under their folder. */
    private val INDENTS = Array(4) { "  ".repeat(it) }

    /** The `<head>`/`<body>` tags, `<body>`'s direct children and the feeds inside a folder. */
    private const val TAG_LEVEL = 1
    private const val BODY_CONTENT_LEVEL = 2
    private const val FEED_IN_FOLDER_LEVEL = 3

    private const val RSS = "rss"
    private const val YOUTUBE = "youtube"

    // 04 Variant URLs: the bit order and the playlist prefixes the attribute spells out.
    private const val YT_LONG_FORM = 1
    private const val YT_SHORTS = 2
    private const val YT_LIVE = 4

    // English names indexed by the ISO enum order: `Month` starts at January, `DayOfWeek` at Monday.
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** 05 Structure: `displayTitle.lowercase()`, then `displayTitle`, then `xmlUrl`, in code-point order. */
    private val FEED_ORDER =
        compareBy<ExportFeed>({ it.displayTitle.lowercase() }, { it.displayTitle }, { it.xmlUrl })

    public fun write(
        out: BufferedSink,
        document: ExportDocument,
        layout: OpmlLayout,
    ) {
        out.writeUtf8("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.writeUtf8("<opml version=\"2.0\" xmlns:nd=\"urn:neutrodyne:opml:1\">\n")
        writeHead(out, document)
        out.writeUtf8("${INDENTS[TAG_LEVEL]}<body>\n")
        when (layout) {
            OpmlLayout.GROUPED -> writeGrouped(out, document)
            OpmlLayout.FLAT -> writeFlat(out, document)
        }
        out.writeUtf8("${INDENTS[TAG_LEVEL]}</body>\n")
        out.writeUtf8("</opml>\n")
    }

    private fun writeHead(
        out: BufferedSink,
        document: ExportDocument,
    ) {
        out.writeUtf8("${INDENTS[TAG_LEVEL]}<head>\n")
        writeElement(out, "title", document.title)
        writeElement(out, "dateCreated", formatDate(document.createdAt))
        writeElement(out, "docs", "http://opml.org/spec2.opml")
        out.writeUtf8("${INDENTS[TAG_LEVEL]}</head>\n")
    }

    private fun writeGrouped(
        out: BufferedSink,
        document: ExportDocument,
    ) {
        val feeds = document.feeds.sortedWith(FEED_ORDER)
        val primaryKeys = feeds.map { primaryGroupKey(it, document.groups) }

        for (group in document.groups) {
            val members = feeds.filterIndexed { index, _ -> primaryKeys[index] == group.key }
            writeFolder(out, group, members, document.groups)
        }
        for ((index, feed) in feeds.withIndex()) {
            if (primaryKeys[index] != null) continue
            writeFeed(out, feed, document.groups, BODY_CONTENT_LEVEL)
        }
    }

    private fun writeFlat(
        out: BufferedSink,
        document: ExportDocument,
    ) {
        for (feed in document.feeds.sortedWith(FEED_ORDER)) {
            writeFeed(out, feed, document.groups, BODY_CONTENT_LEVEL)
        }
    }

    private fun writeFolder(
        out: BufferedSink,
        group: ExportGroup,
        members: List<ExportFeed>,
        groups: List<ExportGroup>,
    ) {
        out.writeUtf8(INDENTS[BODY_CONTENT_LEVEL])
        out.writeUtf8("<outline")
        writeAttribute(out, "text", group.name)
        writeAttribute(out, "title", group.name)
        group.colorArgb?.let { writeAttribute(out, "nd:groupColor", formatColor(it)) }
        group.iconKey?.let { writeAttribute(out, "nd:groupIcon", it) }
        if (members.isEmpty()) {
            out.writeUtf8("/>\n")
            return
        }
        out.writeUtf8(">\n")
        for (feed in members) writeFeed(out, feed, groups, FEED_IN_FOLDER_LEVEL)
        out.writeUtf8(INDENTS[BODY_CONTENT_LEVEL])
        out.writeUtf8("</outline>\n")
    }

    private fun writeFeed(
        out: BufferedSink,
        feed: ExportFeed,
        groups: List<ExportGroup>,
        level: Int,
    ) {
        val membership = feed.groupKeys.toSet()
        val matched = groups.filter { it.key in membership }

        out.writeUtf8(INDENTS[level])
        out.writeUtf8("<outline")
        writeAttribute(out, "type", RSS)
        writeAttribute(out, "text", feed.displayTitle)
        writeAttribute(out, "title", feed.feedTitle)
        writeAttribute(out, "xmlUrl", feed.xmlUrl)
        feed.htmlUrl?.let { writeAttribute(out, "htmlUrl", it) }
        feed.language?.let { writeAttribute(out, "language", it) }
        category(matched)?.let { writeAttribute(out, "category", it) }
        writeAttribute(out, "nd:source", if (feed.youtube) YOUTUBE else RSS)
        if (feed.youtube) ytVariantNames(feed.ytVariants)?.let { writeAttribute(out, "nd:ytVariants", it) }
        out.writeUtf8("/>\n")
    }

    private fun writeElement(
        out: BufferedSink,
        name: String,
        value: String,
    ) {
        out.writeUtf8(INDENTS[BODY_CONTENT_LEVEL])
        out.writeUtf8("<$name>")
        out.writeUtf8(escape(value))
        out.writeUtf8("</$name>\n")
    }

    private fun writeAttribute(
        out: BufferedSink,
        name: String,
        value: String,
    ) {
        out.writeUtf8(" $name=\"")
        out.writeUtf8(escape(value))
        out.writeUtf8("\"")
    }

    /** The first member group in group order, or null when the feed belongs to no exported group. */
    private fun primaryGroupKey(
        feed: ExportFeed,
        groups: List<ExportGroup>,
    ): String? {
        val membership = feed.groupKeys.toSet()
        return groups.firstOrNull { it.key in membership }?.key
    }

    private fun category(groups: List<ExportGroup>): String? {
        if (groups.isEmpty()) return null
        return groups.joinToString(",") { encodeCategoryName(it.name) }
    }

    /** 05 Attributes: only `%`, `,` and `/` are percent-encoded, so a name never splits or reads as a path. */
    private fun encodeCategoryName(name: String): String {
        if ('%' !in name && ',' !in name && '/' !in name) return name
        val encoded = StringBuilder(name.length + 8)
        for (char in name) {
            when (char) {
                '%' -> encoded.append("%25")
                ',' -> encoded.append("%2C")
                '/' -> encoded.append("%2F")
                else -> encoded.append(char)
            }
        }
        return encoded.toString()
    }

    private fun ytVariantNames(bits: Int?): String? {
        if (bits == null) return null
        val names = mutableListOf<String>()
        if (bits and YT_LONG_FORM != 0) names += "UULF"
        if (bits and YT_SHORTS != 0) names += "UUSH"
        if (bits and YT_LIVE != 0) names += "UULV"
        return names.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    /** `#RRGGBB`, uppercase hex of the low 24 bits; the alpha byte is dropped (05 Attributes). */
    private fun formatColor(argb: Int): String {
        val rgb = argb and 0xFFFFFF
        return buildString(7) {
            append('#')
            for (shift in 20 downTo 0 step 4) append(HEX_DIGITS[(rgb shr shift) and 0xF])
        }
    }

    /**
     * XML escaping plus the invalid-xml-character strip of 05 Attributes, so one bad title can never
     * abort an export. Escaped: `& < > "` and newline, CR and tab; stripped first: `U+0000–U+0008`,
     * `U+000B`, `U+000C`, `U+000E–U+001F`, `U+FFFE`, `U+FFFF` and lone surrogates. ZWJ and variation
     * selectors are valid XML and survive.
     */
    private fun escape(input: String): String {
        val escaped = StringBuilder(input.length)
        var index = 0
        while (index < input.length) {
            val char = input[index]
            if (char.isHighSurrogate()) {
                val low = input.getOrNull(index + 1)
                if (low != null && low.isLowSurrogate()) {
                    escaped.append(char).append(low)
                    index += 2
                    continue
                }
                index++
                continue
            }
            if (char.isLowSurrogate() || isInvalidXmlChar(char)) {
                index++
                continue
            }
            when (char) {
                '&' -> escaped.append("&amp;")
                '<' -> escaped.append("&lt;")
                '>' -> escaped.append("&gt;")
                '"' -> escaped.append("&quot;")
                '\n' -> escaped.append("&#10;")
                '\r' -> escaped.append("&#13;")
                '\t' -> escaped.append("&#9;")
                else -> escaped.append(char)
            }
            index++
        }
        return escaped.toString()
    }

    private fun isInvalidXmlChar(char: Char): Boolean =
        char <= '\u0008' ||
            char == '\u000B' ||
            char == '\u000C' ||
            char in '\u000E'..'\u001F' ||
            char == '\uFFFE' ||
            char == '\uFFFF'

    /** RFC 822 `EEE, dd MMM yyyy HH:mm:ss GMT` in UTC with fixed English names, no `String.format`. */
    private fun formatDate(epochMillis: Long): String {
        val time =
            kotlin.time.Instant
                .fromEpochMilliseconds(epochMillis)
                .toLocalDateTime(TimeZone.UTC)
        val date = time.date
        return buildString {
            append(WEEKDAYS[date.dayOfWeek.ordinal])
            append(", ")
            appendPadded(this, date.day, 2)
            append(' ')
            append(MONTHS[date.month.ordinal])
            append(' ')
            appendPadded(this, date.year, 4)
            append(' ')
            appendPadded(this, time.hour, 2)
            append(':')
            appendPadded(this, time.minute, 2)
            append(':')
            appendPadded(this, time.second, 2)
            append(" GMT")
        }
    }

    private fun appendPadded(
        out: StringBuilder,
        value: Int,
        minWidth: Int,
    ) {
        val digits = value.toString()
        repeat(minWidth - digits.length) { out.append('0') }
        out.append(digits)
    }

    private const val HEX_DIGITS = "0123456789ABCDEF"
}
