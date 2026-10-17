// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.archive

import ch.lkmc.neutrodyne.feeds.importing.ImportSourceSniffer
import ch.lkmc.neutrodyne.feeds.importing.SniffResult
import ch.lkmc.neutrodyne.feeds.importing.SniffedFormat
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.buffer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sniffer's ZIP row against real archives: `ZipArchiveWriter` output for the well-formed
 * cases, `java.util.zip` fixtures from [ZipTestFiles] for the shapes no compliant writer emits.
 */
class ImportSourceSnifferZipTest {
    private fun sniff(path: Path) =
        ImportSourceSniffer.sniff(
            open = { FileSystem.SYSTEM.source(path) },
            archivePath = path,
            archives = ZipGuard(),
        )

    @Test
    fun aWrittenBackupZipIsRecognized() {
        val path =
            tempWrittenZip(
                "manifest.json" to """{"format":"neutrodyne-backup"}""",
                "library.json" to """{"podcasts":[]}""",
            )
        assertEquals(SniffResult.Recognized(SniffedFormat.NEUTRODYNE_BACKUP), sniff(path))
    }

    @Test
    fun aWrittenTakeoutZipIsRecognized() {
        val path = tempWrittenZip("Subscriptions.CSV" to "Channel Id,Channel Url,Channel Title")
        assertEquals(SniffResult.Recognized(SniffedFormat.TAKEOUT_CSV), sniff(path))
    }

    @Test
    fun aForeignZipIsUnsupported() {
        val path = tempWrittenZip("readme.txt" to "hello")
        assertEquals(SniffResult.Unsupported("zip"), sniff(path))
    }

    @Test
    fun aHostileZipSurfacesTheGuardReason() {
        // A traversal name — ZipArchiveWriter refuses those, so the fixture is hand-written.
        val path = ZipTestFiles.tempZip(ZipTestFiles.zipBytes(listOf("../evil" to byteArrayOf(1))))
        assertEquals(SniffResult.Hostile("zip_path"), sniff(path))
    }

    @Test
    fun aDamagedZipIsUnsupportedNotHostile() {
        val zip = ZipTestFiles.zipBytes(listOf("a.txt" to "data".encodeToByteArray()))
        val path = ZipTestFiles.tempZip(zip.copyOf(zip.size - 20))
        assertEquals(SniffResult.Unsupported("zip"), sniff(path))
    }

    private fun tempWrittenZip(vararg entries: Pair<String, String>): Path {
        val path = Files.createTempFile("sniff", ".zip").toOkioPath()
        ZipArchiveWriter().create(path).use { writer ->
            for ((name, content) in entries) {
                writer.entry(name).buffer().use { it.writeUtf8(content) }
            }
        }
        return path
    }
}
