// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.youtube

import ch.lkmc.neutrodyne.feeds.backup.ArchiveCaps
import ch.lkmc.neutrodyne.feeds.backup.ArchiveException
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipGuard
import okio.Path.Companion.toPath
import okio.blackholeSink
import okio.buffer
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Counted Takeout limits in the existing mutation runner's 128 MiB heap. */
class TakeoutHostileInputTest {
    @Test
    fun centralDirectoryRejectsTenThousandAndOneEntries() {
        val file = Files.createTempFile("w5-directory", ".zip").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                repeat(10_001) {
                    zip.putNextEntry(ZipEntry("$it.bin"))
                    zip.closeEntry()
                }
            }
            val error =
                assertFailsWith<ArchiveException> {
                    ZipGuard().open(file.absolutePath.toPath(), ArchiveCaps.TAKEOUT).close()
                }
            assertEquals("zip_entries", error.reason)
        } finally {
            file.delete()
        }
    }

    @Test
    fun aggregateInflationStopsAtDecimalTwoHundredFiftyMegabytes() {
        val file = Files.createTempFile("w5-inflation", ".zip").toFile()
        try {
            val chunk = ByteArray(10_000)
            ZipOutputStream(file.outputStream()).use { zip ->
                repeat(51) {
                    zip.putNextEntry(ZipEntry("$it.bin"))
                    repeat(500) { zip.write(chunk) }
                    zip.closeEntry()
                }
            }
            val error =
                assertFailsWith<ArchiveException> {
                    ZipGuard().open(file.absolutePath.toPath(), ArchiveCaps.TAKEOUT).use { reader ->
                        for (name in reader.entryNames) reader.open(name).buffer().use { it.readAll(blackholeSink()) }
                    }
                }
            assertEquals("zip_bomb", error.reason)
        } finally {
            file.delete()
        }
    }
}
