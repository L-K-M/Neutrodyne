// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.youtube

import ch.lkmc.neutrodyne.feeds.importing.ImportDocument
import ch.lkmc.neutrodyne.feeds.importing.ImportEntry
import okio.Source

public object UrlListParser {
    public fun parse(source: Source): ImportDocument {
        val text = readImportText(source, MAX_BYTES)
        val lines = text.lineSequence().toList()
        // A trailing line terminator does not add an input line.
        checkItemCount(lines.size - if (text.endsWith('\n') || text.endsWith('\r')) 1 else 0, MAX_LINES)
        val entries =
            lines
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith('#') }
                .mapIndexed { index, line -> ImportEntry(index, line) }
        return ImportDocument("URL_LIST", entries, emptyList())
    }

    private const val MAX_BYTES = 1_000_000L
    private const val MAX_LINES = 5_000
}
