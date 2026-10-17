// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.archive

import ch.lkmc.neutrodyne.feeds.backup.ArchiveWriter
import ch.lkmc.neutrodyne.feeds.backup.ArchiveWriterFactory
import okio.Buffer
import okio.Path
import okio.Sink
import okio.Timeout
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The backup ZIP writer (05 Writing): `java.util.zip.ZipOutputStream` writing DEFLATE entries to
 * [FileSystem.SYSTEM] at the path the caller staged. Every entry carries a fixed DOS timestamp so
 * bytes are reproducible for a fixed JVM/deflater build; the atomic move onto the final name is
 * the caller's job, not this writer's.
 */
public class ZipArchiveWriter : ArchiveWriterFactory {
    override fun create(
        path: Path,
        deflateLevel: Int,
    ): ArchiveWriter {
        // The writer keeps the leaf file stream: `ZipOutputStream`'s own close cannot finish
        // an archive whose entry close already failed (a second `closeEntry` then dies on the
        // ended deflater), so the descriptor is released directly on the failure path.
        val fileStream = FileOutputStream(path.toFile())
        val out = ZipOutputStream(BufferedOutputStream(fileStream))
        try {
            out.setLevel(deflateLevel)
            return Writer(out, fileStream)
        } catch (e: Throwable) {
            try {
                fileStream.close()
            } catch (closeFailure: Throwable) {
                e.addSuppressed(closeFailure)
            }
            throw e
        }
    }

    private class Writer(
        private val out: ZipOutputStream,
        private val fileStream: FileOutputStream,
    ) : ArchiveWriter {
        private var openEntry: EntrySink? = null
        private var closed = false

        override fun entry(name: String): Sink {
            check(!closed) { "archive writer is closed" }
            check(openEntry == null) { "an archive entry is still open; close it before the next" }
            require(isSafeEntryName(name)) { "invalid archive entry name" }
            val entry = ZipEntry(name)
            // setTimeLocal is API 35+ on Android; the legacy setter is available at minSdk.
            entry.time = DOS_TIMESTAMP.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            out.putNextEntry(entry)
            return EntrySink().also { openEntry = it }
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                openEntry?.close()
                out.close()
            } finally {
                // A failed entry close skips the stream's own close; the descriptor is
                // released directly either way.
                runCatching { fileStream.close() }
            }
        }

        /**
         * Streams into the current entry without closing the archive: [close] ends the entry
         * (`closeEntry`), so the writer stays usable for the next one. Writes go through a fixed
         * scratch buffer, never a `byteCount`-sized allocation.
         */
        private inner class EntrySink : Sink {
            private var open = true
            private val scratch = ByteArray(SCRATCH_BYTES)

            override fun write(
                source: Buffer,
                byteCount: Long,
            ) {
                check(open) { "entry sink is closed" }
                var remaining = byteCount
                while (remaining > 0) {
                    val n = source.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
                    out.write(scratch, 0, n)
                    remaining -= n
                }
            }

            override fun flush() {
                out.flush()
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() {
                if (!open) return
                open = false
                this@Writer.openEntry = null
                out.closeEntry()
            }
        }
    }

    private companion object {
        /**
         * The exact DOS epoch is also the JDK's before-1980 sentinel, which adds a timezone-
         * dependent extended timestamp. The next day is representable without that extra field.
         */
        val DOS_TIMESTAMP: LocalDateTime = LocalDateTime.of(1980, 1, 2, 0, 0)
        const val SCRATCH_BYTES: Int = 8 * 1024
    }
}
