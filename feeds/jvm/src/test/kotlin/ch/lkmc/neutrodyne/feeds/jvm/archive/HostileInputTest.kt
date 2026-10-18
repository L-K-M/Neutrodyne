// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.archive

import ch.lkmc.neutrodyne.feeds.backup.ArchiveCaps
import ch.lkmc.neutrodyne.feeds.backup.ArchiveException
import okio.blackholeSink
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The ZIP legs of 05's `HostileInputTest` (M3 acceptance 3): each generated hostile archive is
 * rejected with its declared error in under [LIMIT_MS], without an `OutOfMemoryError` — this class
 * runs in the capped-heap `mutationTest` task (128 MB), so a guard that trusted the headers would
 * die instead of failing (09 Untrusted-input robustness). The XML legs land with `XmlPullOpmlReader`.
 */
class HostileInputTest {
    /** 1 GiB of NULs in ~1 MiB: the classic bomb, rejected by the counted ratio/byte caps. */
    @Test
    fun zipBombHitsTheRatioCap() {
        val path = ZipTestFiles.tempZip(ZipTestFiles.zeroBombBytes("payload.bin", 1L shl 30))
        assertRejects("zip_bomb") {
            ZipGuard().open(path, ArchiveCaps.BACKUP).use { reader ->
                reader.open("payload.bin").buffer().readAll(blackholeSink())
            }
        }
    }

    /** Zip-slip spellings: `../../x`, `/abs`, `a\b` — rejected on names, never extracted. */
    @Test
    fun zipSlipNamesAreRejected() {
        for (name in listOf("../../x", "/abs", "a\\b")) {
            val path = ZipTestFiles.tempZip(ZipTestFiles.zipBytes(listOf(name to byteArrayOf(1))))
            assertRejects("zip_path") { ZipGuard().open(path, ArchiveCaps.BACKUP).close() }
        }
    }

    /** A backup claims 6 entries; 10,000 in the central directory trips the count cap. */
    @Test
    fun zipEntryFloodIsRejected() {
        val entries = (1..10_000).map { "entry-$it.bin" to byteArrayOf(0) }
        val path = ZipTestFiles.tempZip(ZipTestFiles.zipBytes(entries))
        assertRejects("zip_entries") { ZipGuard().open(path, ArchiveCaps.BACKUP).close() }
    }

    /**
     * A 176-byte ZIP64 END claiming 2³⁰ entries makes `ZipFile`'s constructor build an
     * `int[centot * 3]` that overflows negative — `NegativeArraySizeException` on any heap.
     * The guard must reject with [ArchiveException] before `ZipFile` is ever constructed.
     */
    @Test
    fun zip64EndCountOverflowIsRejected() {
        val path = ZipTestFiles.tempZip(ZipTestFiles.zip64EndBomb(centot64 = 0x4000_0000L))
        assertRejects("zip_entries") { ZipGuard().open(path, ArchiveCaps.BACKUP).close() }
    }

    /**
     * The same archive claiming 2²⁹ entries: `int[centot * 3]` is a valid ~6 GiB request that
     * dies with `OutOfMemoryError` on this task's capped heap — again an [ArchiveException],
     * never an `Error`.
     */
    @Test
    fun zip64EndCountOomIsRejected() {
        val path = ZipTestFiles.tempZip(ZipTestFiles.zip64EndBomb(centot64 = 0x2000_0000L))
        assertRejects("zip_entries") { ZipGuard().open(path, ArchiveCaps.BACKUP).close() }
    }

    private fun assertRejects(
        reason: String,
        block: () -> Unit,
    ) {
        val started = System.nanoTime()
        val e = assertFailsWith<ArchiveException>(block = block)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(ArchiveException.Kind.HOSTILE, e.kind)
        assertEquals(reason, e.reason)
        assertTrue(elapsedMs < LIMIT_MS, "rejection took ${elapsedMs}ms for $reason")
    }

    private companion object {
        const val LIMIT_MS = 2_000L
    }
}
