// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.exporting

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.credentials.AtomicMove
import ch.lkmc.neutrodyne.core.data.storagePathsFor
import ch.lkmc.neutrodyne.core.domain.ExportError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSink
import okio.ForwardingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `FileExportDestination` (05 Destinations "Save to file (desktop)", row C3a) over real files:
 * the sibling-temp copy, the fsync-before-rename, the atomic replace and the cleanup on every
 * failure leg — copy, flush, move, cancel. Failure injection goes through the injected Okio
 * `FileSystem` (sink/source) and the `AtomicMove` seam; the old target must stay byte-identical
 * and no `*.tmp` may remain.
 */
class FileExportDestinationTest {
    private lateinit var root: File
    private lateinit var targetDir: File
    private lateinit var source: Path
    private lateinit var sourceFile: File
    private lateinit var destination: FileExportDestination

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("nd-file-destination").toFile()
        targetDir = File(root, "chosen").apply { mkdirs() }
        sourceFile = File(root, "cache/export/neutrodyne-subscriptions-2026-10-17.opml")
        sourceFile.parentFile?.mkdirs()
        sourceFile.writeText(SOURCE_BYTES)
        source = sourceFile.absolutePath.toPath()
        destination = destination()
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    // --- success path ----------------------------------------------------------------------------

    @Test
    fun saveCopiesThePreparedFileToTheChosenTarget() =
        runTest {
            val target = File(targetDir, "feeds.opml")
            assertIs<Outcome.Success<Unit>>(destination.save(source, fileUri(target)))
            assertEquals(SOURCE_BYTES, target.readText())
        }

    @Test
    fun saveLeavesThePreparedSourceIntact() =
        runTest {
            val target = File(targetDir, "feeds.opml")
            assertIs<Outcome.Success<Unit>>(destination.save(source, fileUri(target)))
            assertEquals(SOURCE_BYTES, sourceFile.readText())
        }

    @Test
    fun saveReplacesAnExistingTargetAtomically() =
        runTest {
            val target = File(targetDir, "feeds.opml").apply { writeText("old-bytes") }
            assertIs<Outcome.Success<Unit>>(destination.save(source, fileUri(target)))
            assertEquals(SOURCE_BYTES, target.readText())
            assertNoTemps()
        }

    @Test
    fun saveDecodesEscapedTargetNames() =
        runTest {
            val target = File(targetDir, "meine Feeds ä.opml")
            assertIs<Outcome.Success<Unit>>(destination.save(source, fileUri(target)))
            assertEquals(SOURCE_BYTES, target.readText())
        }

    @Test
    fun saveLeavesNoTempBehindOnSuccess() =
        runTest {
            val target = File(targetDir, "feeds.opml")
            assertIs<Outcome.Success<Unit>>(destination.save(source, fileUri(target)))
            assertNoTemps()
        }

    // --- destination URI validation ---------------------------------------------------------------

    @Test
    fun nonFileSchemesAreRejected() =
        runTest {
            for (uri in listOf("content://saf/tree/doc", "https://e.example/x.opml", "not a uri")) {
                assertEquals(
                    Outcome.Failure(ExportError.DestinationUnavailable),
                    destination.save(source, uri),
                    "uri=$uri",
                )
            }
            assertNoTemps()
        }

    @Test
    fun remoteAuthorityIsRejected() =
        runTest {
            assertEquals(
                Outcome.Failure(ExportError.DestinationUnavailable),
                destination.save(source, "file://fileserver/share/feeds.opml"),
            )
        }

    @Test
    fun missingTargetDirectoryIsRejected() =
        runTest {
            val target = File(File(root, "no-such-dir"), "feeds.opml")
            assertEquals(
                Outcome.Failure(ExportError.DestinationUnavailable),
                destination.save(source, fileUri(target)),
            )
        }

    // --- failure legs: copy, flush, move, cancel ---------------------------------------------------

    @Test
    fun sourceReadFailureReportsIoAndKeepsTheTarget() =
        runTest {
            val target = File(targetDir, "feeds.opml").apply { writeText("old") }
            val dest =
                destination(
                    fs =
                        object : ForwardingFileSystem(FileSystem.SYSTEM) {
                            override fun source(file: Path): Source {
                                if (file == source) throw IOException("source gone")
                                return super.source(file)
                            }
                        },
                )
            assertEquals(
                Outcome.Failure(ExportError.Io),
                dest.save(source, fileUri(target)),
            )
            assertEquals("old", target.readText())
            assertNoTemps()
        }

    @Test
    fun sinkOpenFailurePreservesTheTarget() =
        runTest {
            val target = File(targetDir, "feeds.opml").apply { writeText("old") }
            val dest = destination(fs = failingSink { throw IOException("disk full") })
            assertEquals(
                Outcome.Failure(ExportError.DestinationUnavailable),
                dest.save(source, fileUri(target)),
            )
            assertEquals("old", target.readText())
            assertNoTemps()
        }

    @Test
    fun writeFailurePreservesTheTarget() =
        runTest {
            val target = File(targetDir, "feeds.opml").apply { writeText("old") }
            val dest =
                destination(
                    fs =
                        failingSink {
                            object : Sink {
                                override fun write(
                                    source: Buffer,
                                    byteCount: Long,
                                ) = throw IOException("write fault")

                                override fun flush() {}

                                override fun timeout() = okio.Timeout.NONE

                                override fun close() {}
                            }
                        },
                )
            assertEquals(
                Outcome.Failure(ExportError.DestinationUnavailable),
                dest.save(source, fileUri(target)),
            )
            assertEquals("old", target.readText())
            assertNoTemps()
        }

    @Test
    fun flushFailurePreservesTheTarget() =
        runTest {
            val target = File(targetDir, "feeds.opml").apply { writeText("old") }
            val dest =
                destination(
                    fs =
                        failingSink { delegate ->
                            object : ForwardingSink(delegate) {
                                override fun flush() = throw IOException("flush fault")
                            }
                        },
                )
            assertEquals(
                Outcome.Failure(ExportError.DestinationUnavailable),
                dest.save(source, fileUri(target)),
            )
            assertEquals("old", target.readText())
            assertNoTemps()
        }

    @Test
    fun moveRefusalPreservesTheTarget() =
        runTest {
            val target = File(targetDir, "feeds.opml").apply { writeText("old") }
            val dest =
                destination(
                    move =
                        AtomicMove { _, _ ->
                            throw IOException("target is locked")
                        },
                )
            assertEquals(
                Outcome.Failure(ExportError.DestinationUnavailable),
                dest.save(source, fileUri(target)),
            )
            assertEquals("old", target.readText())
            assertNoTemps()
        }

    @Test
    fun unsupportedAtomicMoveIsNeverASilentNonAtomicWrite() =
        runTest {
            // A filesystem without ATOMIC_MOVE (some network shares) gets an honest failure —
            // the target is untouched and no direct-copy fallback pretends atomicity.
            val target = File(targetDir, "feeds.opml").apply { writeText("old") }
            val dest =
                destination(
                    move =
                        AtomicMove { source, target ->
                            throw AtomicMoveNotSupportedException(
                                source.toString(),
                                target.toString(),
                                "not supported",
                            )
                        },
                )
            assertEquals(
                Outcome.Failure(ExportError.DestinationUnavailable),
                dest.save(source, fileUri(target)),
            )
            assertEquals("old", target.readText())
            assertNoTemps()
        }

    @Test
    fun destinationIsADirectory() =
        runTest {
            // Renaming a file over a directory fails on every desktop FS — honest failure.
            val asDir = File(targetDir, "taken.opml").apply { mkdirs() }
            assertEquals(
                Outcome.Failure(ExportError.DestinationUnavailable),
                destination.save(source, fileUri(asDir)),
            )
            assertTrue(asDir.isDirectory)
            assertNoTemps()
        }

    @Test
    fun cancelledCopyLeavesTheTargetAndRemovesTheTemp() =
        runBlocking {
            val target = File(targetDir, "feeds.opml").apply { writeText("old") }
            val started = CountDownLatch(1)
            val gate = CountDownLatch(1)
            val dest =
                destination(
                    fs =
                        object : ForwardingFileSystem(FileSystem.SYSTEM) {
                            override fun source(file: Path): Source {
                                val real = super.source(file)
                                if (file != source) return real
                                return object : ForwardingSource(real) {
                                    private var gated = false

                                    override fun read(
                                        sink: Buffer,
                                        byteCount: Long,
                                    ): Long {
                                        if (!gated) {
                                            gated = true
                                            started.countDown()
                                            check(gate.await(10, TimeUnit.SECONDS))
                                        }
                                        return super.read(sink, byteCount)
                                    }
                                }
                            }
                        },
                )
            // `async` needs a real dispatcher: this `runBlocking` thread is parked on the
            // latch below while the copy's first read blocks on the gate inside `save`'s
            // own IO context.
            val save = async(Dispatchers.IO) { dest.save(source, fileUri(target)) }
            check(started.await(10, TimeUnit.SECONDS))
            save.cancel()
            gate.countDown()

            assertFailsWith<CancellationException> { save.await() }
            assertEquals("old", target.readText())
            assertNoTemps()
        }

    // --- shareUri -----------------------------------------------------------------------------------

    @Test
    fun shareUriIsTheEscapedFileUriOfTheExportLeaf() {
        val uri = destination.shareUri("neutrodyne-sci fi-2026-10-17.opml")
        val expected =
            File(root, "cache/export/neutrodyne-sci fi-2026-10-17.opml")
        assertEquals(expected.toPath().toUri().toASCIIString(), uri)
        // The minted URI decodes back to exactly the export-dir leaf — no escapes drift.
        assertEquals(
            expected.absoluteFile.toPath(),
            java.nio.file.Paths
                .get(java.net.URI(uri!!)),
        )
    }

    @Test
    fun shareUriRejectsTraversalAndNonLeafNames() {
        for (name in listOf("", ".", "..", "a/b.opml", "a\\b.opml", "..\\x.opml", "sub/../x")) {
            assertNull(destination.shareUri(name), "name=$name")
        }
    }

    // --- helpers -------------------------------------------------------------------------------------

    private fun destination(
        fs: FileSystem = FileSystem.SYSTEM,
        move: AtomicMove? = null,
    ) = if (move == null) {
        // The production default (`ATOMIC_MOVE + REPLACE_EXISTING` via `Files.move`).
        FileExportDestination(storagePathsFor(root), fs, Dispatchers.IO)
    } else {
        FileExportDestination(storagePathsFor(root), fs, Dispatchers.IO, move)
    }

    /** A filesystem whose temp sinks misbehave: [wrap] decides the failure mode. */
    private fun failingSink(wrap: (Sink) -> Sink): FileSystem =
        object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun sink(
                file: Path,
                mustCreate: Boolean,
            ): Sink {
                if (file.name.startsWith(TEMP_MARKER)) return wrap(super.sink(file, mustCreate))
                return super.sink(file, mustCreate)
            }
        }

    private fun fileUri(file: File): String = file.toPath().toUri().toASCIIString()

    private fun assertNoTemps() {
        val leftovers = targetDir.list()?.filter { it.startsWith(TEMP_MARKER) } ?: emptyList()
        assertEquals(emptyList(), leftovers)
    }

    private companion object {
        const val SOURCE_BYTES = "<opml version=\"2.0\">fixture</opml>"

        /** `FileExportDestination`'s owned-temp prefix — mirrored for the failure seam. */
        const val TEMP_MARKER = ".neutrodyne-export-"
    }
}
