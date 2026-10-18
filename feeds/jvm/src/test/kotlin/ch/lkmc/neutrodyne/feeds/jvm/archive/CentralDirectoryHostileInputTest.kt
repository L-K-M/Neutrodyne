// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.archive

import ch.lkmc.neutrodyne.feeds.backup.ArchiveCaps
import ch.lkmc.neutrodyne.feeds.backup.ArchiveException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Tiny headers must not force a directory allocation larger than mutationTest's 128 MiB heap. */
class CentralDirectoryHostileInputTest {
    @Test
    fun oversizedClassicDirectoryIsRejectedWithoutAnError() {
        rejectsCorrupt(ZipTestFiles.endLyingZip(DECLARED_ENTRIES, DECLARED_CENTRAL_BYTES))
    }

    @Test
    fun oversizedZip64DirectoryIsRejectedWithoutAnError() {
        rejectsCorrupt(ZipTestFiles.zip64EndBomb(DECLARED_ENTRIES.toLong(), DECLARED_CENTRAL_BYTES))
    }

    @Test
    fun overflowingZip64LocatorKeepsTheTypedClassicFallback() {
        val path = ZipTestFiles.tempZip(ZipTestFiles.zip64EndBomb(1, locatorOffset = Long.MAX_VALUE - 1))
        val failure =
            assertFailsWith<ArchiveException> { ZipGuard().open(path, ArchiveCaps.BACKUP).close() }

        assertEquals(ArchiveException.Kind.HOSTILE, failure.kind)
        assertEquals("zip_entries", failure.reason)
    }

    private fun rejectsCorrupt(bytes: ByteArray) {
        val path = ZipTestFiles.tempZip(bytes)
        val failure =
            assertFailsWith<ArchiveException> {
                ZipGuard().open(path, ArchiveCaps.BACKUP.copy(maxEntries = DECLARED_ENTRIES)).close()
            }
        assertEquals(ArchiveException.Kind.CORRUPT, failure.kind)
    }

    private companion object {
        const val DECLARED_ENTRIES = 1_000
        const val DECLARED_CENTRAL_BYTES = 190_000_000L
    }
}
