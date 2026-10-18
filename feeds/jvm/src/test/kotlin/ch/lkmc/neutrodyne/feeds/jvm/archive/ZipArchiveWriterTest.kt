// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.archive

import okio.Buffer
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.buffer
import org.junit.Assume.assumeTrue
import java.io.IOException
import java.nio.file.Files
import java.util.Random
import java.util.TimeZone
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `ZipArchiveWriter` against plain `java.util.zip` reads (05 Writing): entry order, the
 * one-entry-at-a-time rule, deterministic output and the deflate level.
 */
class ZipArchiveWriterTest {
    @Test
    fun writtenArchiveReadsBackInOrder() {
        val entries =
            listOf(
                "episodes.jsonl" to "line1\nline2\n".encodeToByteArray(),
                "library.json" to "{}".encodeToByteArray(),
                "manifest.json" to "{}".encodeToByteArray(),
            )
        val path = write(entries)

        ZipFile(path.toFile()).use { zip ->
            assertEquals(
                entries.map { it.first },
                zip
                    .entries()
                    .asSequence()
                    .map { it.name }
                    .toList(),
            )
            for ((name, content) in entries) {
                zip.getInputStream(zip.getEntry(name)).use { stream ->
                    assertContentEquals(content, stream.readBytes())
                }
            }
        }
    }

    @Test
    fun identicalContentProducesIdenticalBytes() {
        val entries = listOf("a" to "first".encodeToByteArray(), "b" to ByteArray(4096) { it.toByte() })
        val one = write(entries)
        val two = write(entries)
        assertContentEquals(Files.readAllBytes(one.toFile().toPath()), Files.readAllBytes(two.toFile().toPath()))
    }

    @Test
    fun outputDoesNotDependOnTheDefaultTimeZone() {
        val previous = TimeZone.getDefault()
        val entries = listOf("a" to "payload".encodeToByteArray())
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val utc = Files.readAllBytes(write(entries).toFile().toPath())
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Apia"))
            val apia = Files.readAllBytes(write(entries).toFile().toPath())

            assertContentEquals(utc, apia)
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun oneEntryOpenAtATime() {
        val path = Files.createTempFile("zip-writer", ".zip").toOkioPath()
        ZipArchiveWriter().create(path).use { writer ->
            writer.entry("a").use { sink ->
                assertFailsWith<IllegalStateException> { writer.entry("b") }
            }
            writer.entry("b").close()
        }
    }

    @Test
    fun thePlatformWriterRejectsDuplicateEntryNames() {
        val path = Files.createTempFile("zip-writer", ".zip").toOkioPath()
        ZipArchiveWriter().create(path).use { writer ->
            writer.entry("a").close()

            assertFailsWith<ZipException> { writer.entry("a") }
        }
    }

    @Test
    fun writerCloseEndsTheOpenEntry() {
        val path = Files.createTempFile("zip-writer", ".zip").toOkioPath()
        val writer = ZipArchiveWriter().create(path)
        val sink = writer.entry("only").buffer()
        sink.writeUtf8("payload")
        sink.flush()
        writer.close()

        ZipFile(path.toFile()).use { zip ->
            assertEquals("payload", zip.getInputStream(zip.getEntry("only")).readBytes().decodeToString())
        }
    }

    @Test
    fun deflateLevelIsHonoured() {
        val compressible = ByteArray(64 * 1024)
        val stored = write(listOf("z" to compressible), deflateLevel = 0)
        val compressed = write(listOf("z" to compressible), deflateLevel = 9)
        assertTrue(Files.size(stored.toFile().toPath()) > Files.size(compressed.toFile().toPath()))
    }

    @Test
    fun unsafeEntryNamesAreRejected() {
        val path = Files.createTempFile("zip-writer", ".zip").toOkioPath()
        ZipArchiveWriter().create(path).use { writer ->
            for (name in listOf("../x", "..", "a/../b", "/abs", "a\\b")) {
                assertFailsWith<IllegalArgumentException>(name) { writer.entry(name) }
            }
        }
    }

    @Test
    fun invalidLevelLeavesNoOpenFile() {
        assumeTrue(
            Files.isDirectory(
                java.nio.file.Path
                    .of(PROC_FD),
            ),
        )
        val before = openFdCount()
        repeat(8) {
            assertFailsWith<IllegalArgumentException> {
                ZipArchiveWriter().create(Files.createTempFile("zip-writer", ".zip").toOkioPath(), deflateLevel = -2)
            }
        }
        assertEquals(before, openFdCount())
    }

    @Test
    fun closeReleasesTheFileWhenTheEntryCloseFails() {
        // /dev/full fails every write; an entry big enough to flush mid-write leaves
        // closeEntry() failing too, which used to skip the stream's close entirely.
        assumeTrue(
            Files.isDirectory(
                java.nio.file.Path
                    .of(PROC_FD),
            ),
        )
        val devFull =
            java.nio.file.Path
                .of("/dev/full")
        assumeTrue(Files.isWritable(devFull))
        val before = openFdCount()
        repeat(4) {
            val writer = ZipArchiveWriter().create(devFull.toOkioPath())
            val sink = writer.entry("a")
            val bytes = ByteArray(64 * 1024).also { Random(7).nextBytes(it) }
            val payload = Buffer().write(bytes)
            assertFailsWith<IOException> { sink.write(payload, payload.size) }
            assertFailsWith<IOException> { writer.close() }
        }
        assertEquals(before, openFdCount())
    }

    private fun openFdCount(): Long =
        Files
            .list(
                java.nio.file.Path
                    .of(PROC_FD),
            ).use { it.count() }

    private companion object {
        /** Linux fd directory; the leak tests skip where it is absent. */
        const val PROC_FD = "/proc/self/fd"
    }

    private fun write(
        entries: List<Pair<String, ByteArray>>,
        deflateLevel: Int = 6,
    ): Path {
        val path = Files.createTempFile("zip-writer", ".zip").toOkioPath()
        ZipArchiveWriter().create(path, deflateLevel).use { writer ->
            for ((name, content) in entries) {
                writer.entry(name).buffer().use { it.write(content) }
            }
        }
        return path
    }
}
