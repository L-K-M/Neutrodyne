// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.feeds.backup.ArchiveCaps
import ch.lkmc.neutrodyne.feeds.backup.ArchiveException
import ch.lkmc.neutrodyne.feeds.backup.ArchiveReader
import ch.lkmc.neutrodyne.feeds.backup.ArchiveReaderFactory
import okio.Buffer
import okio.Path
import okio.Source

/**
 * In-memory [ArchiveReaderFactory] for consumer tests (09 `:core:testing` inventory). Serves the
 * configured entries by name and records every [open] call for assertions. It deliberately does
 * not enforce the caps — cap behaviour is the real guard's contract, tested by `ZipGuardTest`;
 * a consumer test needing a cap failure injects [openError] instead.
 */
public class FakeArchiveReaderFactory(
    private val entries: Map<String, ByteArray> = emptyMap(),
    private val openError: ArchiveException? = null,
) : ArchiveReaderFactory {
    public data class RecordedOpen(
        val path: Path,
        val caps: ArchiveCaps,
    )

    public val calls: MutableList<RecordedOpen> = mutableListOf()

    override fun open(
        path: Path,
        caps: ArchiveCaps,
    ): ArchiveReader {
        calls += RecordedOpen(path, caps)
        openError?.let { throw it }
        return Reader(entries)
    }

    private class Reader(
        entries: Map<String, ByteArray>,
    ) : ArchiveReader {
        private val entries = entries.toMap()

        override val entryNames: List<String> = this.entries.keys.toList()

        override fun open(name: String): Source {
            val bytes = entries[name] ?: throw ArchiveException(ArchiveException.Kind.CORRUPT, "zip")
            return Buffer().write(bytes)
        }

        override fun close() {
        }
    }
}
