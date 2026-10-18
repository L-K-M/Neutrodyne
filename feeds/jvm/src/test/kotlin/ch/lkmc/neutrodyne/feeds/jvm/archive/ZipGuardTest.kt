// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.archive

import ch.lkmc.neutrodyne.feeds.backup.ArchiveCaps
import ch.lkmc.neutrodyne.feeds.backup.ArchiveException
import okio.blackholeSink
import okio.buffer
import java.io.IOException
import java.util.zip.ZipException
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
    fun directoryEntryDoesNotSatisfyExactName() {
        // `ZipFile.getEntry` falls back to "name/" when "name" is absent: without the exact-name
        // check, opening "manifest.json" would silently read the directory entry instead.
        val path = ZipTestFiles.tempZip(ZipTestFiles.zipBytes(listOf("manifest.json/" to byteArrayOf())))
        ZipGuard().open(path, ArchiveCaps.BACKUP).use { reader ->
            assertEquals("zip", corruptOf { reader.open("manifest.json") })
        }
    }

    @Test
    fun declaredEntryCountOverCapIsHostile() {
        // The END record itself claims more entries than the cap allows; the guard rejects it
        // before `ZipFile` allocates from the claim.
        val path =
            ZipTestFiles.tempZip(
                ZipTestFiles.endLyingZip(declaredEntries = ArchiveCaps.BACKUP.maxEntries + 1, declaredCenlen = 47),
            )
        assertEquals("zip_entries", hostileOf { guard.open(path, ArchiveCaps.BACKUP) })
    }

    @Test
    fun centralDirectoryShorterThanDeclaredCountIsCorrupt() {
        // Two declared entries cannot fit in 47 central-directory bytes (records are ≥ 46).
        val path = ZipTestFiles.tempZip(ZipTestFiles.endLyingZip(declaredEntries = 2, declaredCenlen = 47))
        assertEquals("zip", corruptOf { guard.open(path, ArchiveCaps.BACKUP) })
    }

    @Test
    fun centralDirectoryBeyondCapRoomIsHostile() {
        // A declared directory bigger than the cap's worst case only exists to force the
        // constructor's `byte[cenlen]` allocation.
        val caps = ArchiveCaps(maxEntries = 4, maxEntryBytes = 1024, maxTotalBytes = 4096)
        val path =
            ZipTestFiles.tempZip(
                ZipTestFiles.endLyingZip(declaredEntries = 1, declaredCenlen = 4L * 196_651 + 1),
            )
        assertEquals("zip_entries", hostileOf { guard.open(path, caps) })
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
    fun storedEntrySizeCapCountsRealBytes() {
        // A STORED entry takes a different stream class than a DEFLATED one; the same counted
        // caps must still hold.
        val caps = ArchiveCaps(maxEntries = 4, maxEntryBytes = 8, maxTotalBytes = 1L shl 62)
        val path =
            ZipTestFiles.tempZip(
                ZipTestFiles.storedZipBytes(listOf("big" to ByteArray(10_000) { it.toByte() })),
            )
        guard.open(path, caps).use { reader ->
            assertEquals("zip_bomb", hostileOf { reader.open("big").buffer().readAll(blackholeSink()) })
        }
    }

    @Test
    fun storedSlipNameIsHostile() {
        val path = ZipTestFiles.tempZip(ZipTestFiles.storedZipBytes(listOf("../x" to byteArrayOf(1))))
        assertEquals("zip_path", hostileOf { guard.open(path, ArchiveCaps.BACKUP) })
    }

    @Test
    fun corruptedCrcIsDeliveredUnchecked() {
        // `ZipFile` never verifies the CRC: a wrong CEN crc still delivers the entry's bytes
        // without error. Content integrity is the manifest SHA-256 check's job (05 Validation).
        val content = ByteArray(1024) { (it * 7).toByte() }
        val zip = ZipTestFiles.corruptCenCrc(ZipTestFiles.zipBytes(listOf("a" to content)))
        val path = ZipTestFiles.tempZip(zip)
        guard.open(path, ArchiveCaps.BACKUP).use { reader ->
            assertContentEquals(content, reader.open("a").buffer().readByteArray())
        }
    }

    @Test
    fun damagedEntryDataIsCorrupt() {
        // The deflate body is damaged while the central directory stays readable — the archive
        // opens, then the entry's own read reports the damage.
        val zip =
            ZipTestFiles.deflateDamagedZip(
                ZipTestFiles.zipBytes(listOf("a" to ByteArray(4 * 1024) { it.toByte() })),
            )
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

    /**
     * Android's path validator surfaces differently per release: API 34's native zip clears the
     * callback's exception and throws "restricted zip entry name"; API 35+ rethrows "Invalid zip
     * entry path: …". Both are the platform's `..`/leading-`/` rejection, hence `zip_path`.
     */
    @Test
    fun platformPathRejectionsMapToHostileOnEveryAndroidRelease() {
        for (message in listOf("restricted zip entry name", "Invalid zip entry path: ../x")) {
            val error = ZipGuard().mapOpenFailure(ZipException(message))
            assertEquals(ArchiveException.Kind.HOSTILE, error.kind, message)
            assertEquals("zip_path", error.reason, message)
        }
        assertEquals(
            ArchiveException.Kind.CORRUPT,
            ZipGuard().mapOpenFailure(ZipException("zip END header not found")).kind,
        )
    }
}
