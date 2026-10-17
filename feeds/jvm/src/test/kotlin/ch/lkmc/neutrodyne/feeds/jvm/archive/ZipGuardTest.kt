// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.archive

import ch.lkmc.neutrodyne.feeds.backup.ArchiveCaps
import ch.lkmc.neutrodyne.feeds.backup.ArchiveException
import okio.blackholeSink
import okio.buffer
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `ZipGuard` against `java.util.zip`-written archives (05 Archive guard): the valid-archive path,
 * every cap, the name rules Android also enforces (34+) plus the `\` rule it does not, duplicate
 * names, an in-test bomb and damaged containers.
 */
class ZipGuardTest {
    private val guard = ZipGuard()

    @Test
    fun validArchiveReadsEveryEntry() {
        val entries =
            listOf(
                "library.json" to """{"podcasts":[]}""".encodeToByteArray(),
                "episodes.jsonl" to "{}\n{}\n".encodeToByteArray(),
                "manifest.json" to """{"format":"neutrodyne-backup"}""".encodeToByteArray(),
            )
        val path = ZipTestFiles.tempZip(ZipTestFiles.zipBytes(entries))

        ZipGuard().open(path, ArchiveCaps.BACKUP).use { reader ->
            assertEquals(entries.map { it.first }, reader.entryNames)
            for ((name, content) in entries) {
                assertContentEquals(content, reader.open(name).buffer().readByteArray())
            }
        }
    }

    @Test
    fun missingFileStaysAnIoError() {
        val missing = ZipTestFiles.tempZip("no-archive".encodeToByteArray())
        java.io.File(missing.toString()).delete()
        assertFailsWith<IOException> { guard.open(missing, ArchiveCaps.BACKUP) }
    }

    @Test
    fun nonZipFileIsCorrupt() {
        val path = ZipTestFiles.tempZip("this is not a zip file".encodeToByteArray())
        assertEquals("zip", corruptOf { guard.open(path, ArchiveCaps.BACKUP) })
    }

    @Test
    fun truncatedArchiveIsCorrupt() {
        val zip = ZipTestFiles.zipBytes(listOf("a.txt" to "data".encodeToByteArray()))
        // Cutting the tail removes the end-of-central-directory record.
        val path = ZipTestFiles.tempZip(zip.copyOf(zip.size - 20))
        assertEquals("zip", corruptOf { guard.open(path, ArchiveCaps.BACKUP) })
    }

    @Test
    fun unknownEntryIsCorrupt() {
        val path = ZipTestFiles.tempZip(ZipTestFiles.zipBytes(listOf("a" to "x".encodeToByteArray())))
        ZipGuard().open(path, ArchiveCaps.BACKUP).use { reader ->
            assertEquals("zip", corruptOf { reader.open("manifest.json") })
        }
    }

    @Test
    fun entryCountOverflowIsHostile() {
        val entries = (1..ArchiveCaps.BACKUP.maxEntries + 1).map { "e$it" to byteArrayOf(0) }
        val path = ZipTestFiles.tempZip(ZipTestFiles.zipBytes(entries))
        assertEquals("zip_entries", hostileOf { guard.open(path, ArchiveCaps.BACKUP) })
    }

    @Test
    fun entryCountAtCapStillOpens() {
        val entries = (1..ArchiveCaps.BACKUP.maxEntries).map { "e$it" to byteArrayOf(0) }
        val path = ZipTestFiles.tempZip(ZipTestFiles.zipBytes(entries))
        ZipGuard().open(path, ArchiveCaps.BACKUP).use { reader ->
            assertEquals(ArchiveCaps.BACKUP.maxEntries, reader.entryNames.size)
        }
    }

    @Test
    fun slipAndAbsoluteNamesAreHostile() {
        for (name in listOf("../../x", "..", "a/../b", "/abs", "a\\b")) {
            val path = ZipTestFiles.tempZip(ZipTestFiles.zipBytes(listOf(name to byteArrayOf(1))))
            assertEquals("zip_path", hostileOf { guard.open(path, ArchiveCaps.BACKUP) }, name)
        }
    }

    @Test
    fun duplicateNamesAreHostile() {
        val bytes =
            ZipTestFiles.duplicateNameZip(
                name = "dupA",
                other = "dupB",
                content = "same".encodeToByteArray(),
            )
        val path = ZipTestFiles.tempZip(bytes)
        assertEquals("zip_entries", hostileOf { guard.open(path, ArchiveCaps.BACKUP) })
    }

    @Test
    fun entrySizeCapCountsRealBytes() {
        // Declared sizes are not trusted: the cap trips on the counted inflated stream.
        val entry = "big" to ByteArray(10_000) { it.toByte() }
        val caps = ArchiveCaps(maxEntries = 4, maxEntryBytes = 8, maxTotalBytes = 1L shl 62)
        val path = ZipTestFiles.tempZip(ZipTestFiles.zipBytes(listOf(entry)))
        ZipGuard().open(path, caps).use { reader ->
            assertEquals("zip_bomb", hostileOf { reader.open("big").buffer().readAll(blackholeSink()) })
        }
    }

    @Test
    fun totalSizeCapCountsAcrossEntries() {
        val entries = listOf("a" to ByteArray(64), "b" to ByteArray(64))
        val caps = ArchiveCaps(maxEntries = 4, maxEntryBytes = 1024, maxTotalBytes = 100)
        val path = ZipTestFiles.tempZip(ZipTestFiles.zipBytes(entries))
        ZipGuard().open(path, caps).use { reader ->
            reader.open("a").buffer().readAll(blackholeSink())
            assertEquals("zip_bomb", hostileOf { reader.open("b").buffer().readAll(blackholeSink()) })
        }
    }

    @Test
    fun ratioCapTripsAboveTheFloor() {
        // ~96 B of compressed zeros inflate to 8 KiB — far past a 4x ratio once the floor is armed.
        val caps =
            ArchiveCaps(
                maxEntries = 4,
                maxEntryBytes = 1L shl 30,
                maxTotalBytes = 1L shl 62,
                maxRatio = 4,
                ratioFloorBytes = 1024,
            )
        val path = ZipTestFiles.tempZip(ZipTestFiles.zeroBombBytes("zeros", 8 * 1024))
        ZipGuard().open(path, caps).use { reader ->
            assertEquals("zip_bomb", hostileOf { reader.open("zeros").buffer().readAll(blackholeSink()) })
        }
    }

    @Test
    fun belowTheRatioFloorNeverTrips() {
        // The same inflated ratio stays legal under the 16 MiB floor: the check never arms.
        val caps = ArchiveCaps(maxEntries = 4, maxEntryBytes = 1L shl 30, maxTotalBytes = 1L shl 62)
        val inflated = 4L * 1024 * 1024
        val path = ZipTestFiles.tempZip(ZipTestFiles.zeroBombBytes("zeros", inflated))
        ZipGuard().open(path, caps).use { reader ->
            val sink = okio.Buffer()
            reader.open("zeros").buffer().readAll(sink)
            assertEquals(inflated, sink.size)
        }
    }

    @Test
    fun damagedEntryDataIsCorrupt() {
        // The deflate body is damaged while the central directory stays readable — the archive
        // opens, then the entry's own read reports the damage.
        val zip = ZipTestFiles.deflateDamagedZip(ZipTestFiles.zipBytes(listOf("a" to ByteArray(4 * 1024) { it.toByte() })))
        val path = ZipTestFiles.tempZip(zip)
        guard.open(path, ArchiveCaps.BACKUP).use { reader ->
            assertEquals(
                "zip",
                corruptOf { reader.open("a").buffer().readAll(blackholeSink()) },
            )
        }
    }

    private fun hostileOf(block: () -> Unit): String {
        val e = assertFailsWith<ArchiveException>(block = block)
        assertEquals(ArchiveException.Kind.HOSTILE, e.kind)
        assertTrue(e.reason.isNotEmpty())
        return e.reason
    }

    private fun corruptOf(block: () -> Unit): String {
        val e = assertFailsWith<ArchiveException>(block = block)
        assertEquals(ArchiveException.Kind.CORRUPT, e.kind)
        return e.reason
    }
}
