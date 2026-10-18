// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.feeds.backup.ArchiveWriter
import ch.lkmc.neutrodyne.feeds.backup.ArchiveWriterFactory
import okio.Buffer
import okio.Path
import okio.Sink
import okio.Timeout

/**
 * In-memory [ArchiveWriterFactory] for consumer tests (09 `:core:testing` inventory). Each created
 * writer collects its entries in write order; the completed archive lands in [written] when the
 * writer closes, so tests assert both the bytes and the entry order.
 */
public class FakeArchiveWriterFactory : ArchiveWriterFactory {
    public data class WrittenArchive(
        val path: Path,
        val deflateLevel: Int,
        val entries: Map<String, ByteArray>,
    )

    public val written: MutableList<WrittenArchive> = mutableListOf()

    override fun create(
        path: Path,
        deflateLevel: Int,
    ): ArchiveWriter = Writer(path, deflateLevel)

    private inner class Writer(
        private val path: Path,
        private val deflateLevel: Int,
    ) : ArchiveWriter {
        private val entries = LinkedHashMap<String, Buffer>()
        private var openEntry: EntrySink? = null
        private var closed = false

        override fun entry(name: String): Sink {
            check(!closed) { "archive writer is closed" }
            check(openEntry == null) { "an archive entry is still open; close it before the next" }
            check(name !in entries) { "duplicate archive entry name: $name" }
            val buffer = Buffer()
            entries[name] = buffer
            return EntrySink(buffer).also { openEntry = it }
        }

        override fun close() {
            if (closed) return
            closed = true
            openEntry?.close()
            written +=
                WrittenArchive(
                    path,
                    deflateLevel,
                    entries.mapValues { (_, buffer) -> buffer.readByteArray() },
                )
        }

        private inner class EntrySink(
            private val target: Buffer,
        ) : Sink {
            private var open = true

            override fun write(
                source: Buffer,
                byteCount: Long,
            ) {
                check(open) { "entry sink is closed" }
                target.write(source, byteCount)
            }

            override fun flush() {
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() {
                if (!open) return
                open = false
                this@Writer.openEntry = null
            }
        }
    }
}
