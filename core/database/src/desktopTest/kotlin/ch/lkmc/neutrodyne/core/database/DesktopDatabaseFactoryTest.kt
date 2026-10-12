// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import ch.lkmc.neutrodyne.core.common.AppDirs
import org.junit.Assume.assumeTrue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `DesktopDatabaseFactory`'s quarantine files (02 Database builder and connections): the marker
 * is idempotent, the stamp is validated before any filesystem move (it comes back from the
 * `quarantine-pending` file, which is on-disk state), and pruning keeps the numerically newest
 * fresh copy rather than the lexicographically greatest name.
 */
class DesktopDatabaseFactoryTest {
    private val dir = Files.createTempDirectory("m1a-factory")
    private val factory =
        DesktopDatabaseFactory(
            AppDirs(data = dir, config = dir, cache = dir, state = dir, logs = dir, downloadsDefault = dir),
        )

    @Test
    fun markerSetTrueIsIdempotent() {
        factory.quarantineMarker = true
        // A crash-retry path sets the marker again; an existing marker must not fail.
        factory.quarantineMarker = true
        assertTrue(factory.quarantineMarker)
        factory.quarantineMarker = false
        assertFalse(factory.quarantineMarker)
    }

    @Test
    fun quarantineRejectsANonNumericStamp() {
        // The stamp is a directory name read back from on-disk state; anything that is not
        // epoch millis (a path segment like "..", a corrupted line) must fail before moving.
        assertFailsWith<IllegalArgumentException> { factory.quarantine("../escape") }
        assertFailsWith<IllegalArgumentException> { factory.quarantine("not-a-stamp") }
        assertFalse(Files.exists(dir.resolve("escape")), "the stamp escaped the data directory")
    }

    @Test
    fun pruneKeepsTheNumericallyNewestFreshCopy() {
        // 13-digit stamps beat 12-digit ones lexicographically-inverted: "999999999999" sorts
        // above "1000000000000" as a string but is the older stamp.
        val now = 1_000_100_000_000L
        val older = dir.resolve("quarantine/999999999999")
        val newer = dir.resolve("quarantine/1000000000000")
        Files.createDirectories(older)
        Files.createDirectories(newer)

        factory.pruneQuarantine(now)

        assertTrue(Files.exists(newer), "the numerically newest fresh copy must be kept")
        assertFalse(Files.exists(older), "the older copy must be pruned")
    }

    @Test
    fun pruneThrowsWhenACopyCannotBeDeleted() {
        assumeTrue(
            "POSIX permissions require a POSIX file store",
            Files.getFileStore(dir).supportsFileAttributeView(PosixFileAttributeView::class.java),
        )
        val now = 1_700_000_000_000L
        val old = dir.resolve("quarantine/${now - 60_000}")
        val newest = dir.resolve("quarantine/$now")
        Files.createDirectories(old)
        Files.createDirectories(newest)
        Files.writeString(old.resolve(NeutrodyneDatabase.FILE_NAME), "old preserved copy")
        Files.writeString(newest.resolve(NeutrodyneDatabase.FILE_NAME), "newest preserved copy")

        // `deleteRecursively` reports a denied delete only through its Boolean result: the
        // contract is that a failed prune propagates as the open's failure, so a false
        // result must throw instead of reporting a still-present copy as pruned.
        val permissions = Files.getPosixFilePermissions(old)
        try {
            Files.setPosixFilePermissions(old, PosixFilePermissions.fromString("r-x------"))
            assumeTrue("the denial must be real — run unprivileged", !Files.isWritable(old))

            val error = assertFailsWith<IOException> { factory.pruneQuarantine(now) }

            assertEquals("cannot delete $old", error.message)
            assertTrue(Files.exists(old.resolve(NeutrodyneDatabase.FILE_NAME)))
            assertTrue(Files.exists(newest.resolve(NeutrodyneDatabase.FILE_NAME)))
        } finally {
            Files.setPosixFilePermissions(old, permissions)
        }
    }
}
