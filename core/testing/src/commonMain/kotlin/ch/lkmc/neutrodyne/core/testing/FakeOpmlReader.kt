// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.feeds.opml.OpmlDocument
import ch.lkmc.neutrodyne.feeds.opml.OpmlLimits
import ch.lkmc.neutrodyne.feeds.opml.OpmlReadResult
import ch.lkmc.neutrodyne.feeds.opml.OpmlReader
import ch.lkmc.neutrodyne.feeds.opml.ParseMode
import okio.Source

/** Programmable OPML read result: the document to return, or the failure to report. */
class FakeOpmlReader(
    var result: OpmlReadResult =
        OpmlReadResult.Ok(
            OpmlDocument(entries = emptyList(), folders = emptyList(), mode = ParseMode.STRICT),
        ),
) : OpmlReader {
    val sources = mutableListOf<() -> Source>()
    var limitsSeen: OpmlLimits? = null
        private set

    override fun read(
        open: () -> Source,
        limits: OpmlLimits,
    ): OpmlReadResult {
        sources += open
        limitsSeen = limits
        return result
    }
}
