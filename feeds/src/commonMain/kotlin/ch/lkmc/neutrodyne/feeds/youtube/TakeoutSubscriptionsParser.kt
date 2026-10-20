// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.youtube

import ch.lkmc.neutrodyne.feeds.backup.ArchiveCaps
import ch.lkmc.neutrodyne.feeds.backup.ArchiveException
import ch.lkmc.neutrodyne.feeds.backup.ArchiveReaderFactory
import ch.lkmc.neutrodyne.feeds.importing.ImportDocument
import ch.lkmc.neutrodyne.feeds.importing.ImportEntry
import okio.Path
import okio.Source

public object TakeoutSubscriptionsParser {
    /** Sniff only the header and first record; a later truncated preview tail is irrelevant. */
    internal fun looksLikeCsv(text: String): Boolean =
        try {
            val rows = csv(text.removePrefix("\uFEFF"), stopAfterRecords = 2)
            val row = rows.getOrNull(1)
            row != null && (CHANNEL_ID.matches(row[0]) || isChannelUrl(row[1]))
        } catch (_: ImportParseException) {
            false
        }

    public fun parse(source: Source): ImportDocument {
        val rows = csv(readImportText(source, ArchiveCaps.TAKEOUT.maxEntryBytes))
        val entries =
            rows.drop(1).mapIndexed { index, fields ->
                val id = fields.getOrNull(0)?.trim().orEmpty()
                val url = if (CHANNEL_ID.matches(id)) id else fields.getOrNull(1).orEmpty()
                ImportEntry(index, url, fields.getOrNull(2))
            }
        checkItemCount(entries.size)
        return ImportDocument("TAKEOUT_CSV", entries, emptyList())
    }

    /** Opens capped streams only, never extracts. A corrupt candidate fails the whole archive. */
    public fun parseZip(
        path: Path,
        archives: ArchiveReaderFactory,
        acceptsInput: (String) -> Boolean = { CHANNEL_ID.matches(it) || isChannelUrl(it) },
    ): ImportDocument =
        archives.open(path, ArchiveCaps.TAKEOUT).use { reader ->
            val csvs = reader.entryNames.filter { it.endsWith(".csv", ignoreCase = true) }
            if (csvs.size > MAX_CSV_ENTRIES) throw ArchiveException(ArchiveException.Kind.HOSTILE, "zip_entries")
            for (name in csvs) {
                val doc =
                    try {
                        parse(reader.open(name))
                    } catch (e: ImportParseException) {
                        if (e.reason != ImportParseException.Reason.MALFORMED) throw e
                        continue
                    }
                if (doc.entries.any { acceptsInput(it.url) }) return doc
            }
            ImportDocument("TAKEOUT_CSV", emptyList(), emptyList())
        }

    /** Fixed column order allows localized headings; URLs are still classified at the boundary. */
    private fun isChannelUrl(url: String): Boolean =
        url.startsWith("https://www.youtube.com/") || url.startsWith("https://youtube.com/")

    /** RFC 4180: quotes may contain commas and line breaks; text after a closing quote is invalid. */
    private fun csv(
        text: String,
        stopAfterRecords: Int = Int.MAX_VALUE,
    ): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var closed = false
        var i = 0

        fun finishField() {
            row += field.toString()
            field.clear()
            closed = false
        }

        fun finishRow() {
            finishField()
            if (row.any { it.isNotEmpty() }) {
                if (row.size != COLUMNS) throw ImportParseException(ImportParseException.Reason.MALFORMED)
                rows += row
                checkItemCount(rows.size, JSON_ITEMS + 1)
            }
            row = ArrayList()
        }
        while (i < text.length) {
            val char = text[i++]
            if (quoted) {
                if (char != '"') {
                    field.append(char)
                    continue
                }
                if (i < text.length && text[i] == '"') {
                    field.append('"')
                    i++
                    continue
                }
                quoted = false
                closed = true
                continue
            }
            when (char) {
                '"' -> {
                    if (field.isNotEmpty() || closed) throw ImportParseException(ImportParseException.Reason.MALFORMED)
                    quoted = true
                }

                ',' -> {
                    finishField()
                }

                '\r', '\n' -> {
                    if (char == '\r' && i < text.length && text[i] == '\n') i++
                    finishRow()
                    if (rows.size >= stopAfterRecords) return rows
                }

                else -> {
                    if (closed) throw ImportParseException(ImportParseException.Reason.MALFORMED)
                    field.append(char)
                }
            }
        }
        if (quoted) throw ImportParseException(ImportParseException.Reason.MALFORMED)
        if (field.isNotEmpty() || row.isNotEmpty() || closed) finishRow()
        return rows
    }

    private const val COLUMNS = 3
    private const val MAX_CSV_ENTRIES = 50
}
