// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.feeds.backup.ArchiveWriter
import ch.lkmc.neutrodyne.feeds.backup.ArchiveWriterFactory
import okio.Path
import okio.Sink
import okio.sink
import java.io.FilterOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Server-local JDK adapter: the server must not import the client JVM feeds module. */
internal object ServerZipWriter : ArchiveWriterFactory {
    override fun create(
        path: Path,
        deflateLevel: Int,
    ): ArchiveWriter =
        object : ArchiveWriter {
            private val zip =
                ZipOutputStream(
                    Files.newOutputStream(
                        java.nio.file.Path
                            .of(path.toString()),
                    ),
                ).apply { setLevel(deflateLevel) }
            private val names = mutableSetOf<String>()
            private var open = false

            override fun entry(name: String): Sink {
                check(!open)
                require(name.matches(Regex("[a-z.]+")) && names.add(name))
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                open = true
                return object : FilterOutputStream(zip) {
                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) = zip.write(bytes, offset, length)

                    override fun close() {
                        if (!open) return
                        zip.closeEntry()
                        open = false
                    }
                }.sink()
            }

            override fun close() = zip.close()
        }
}
