// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.newNetworkClients
import ch.lkmc.neutrodyne.core.data.storagePathsFor
import ch.lkmc.neutrodyne.core.data.testClassifier
import ch.lkmc.neutrodyne.core.data.testUserAgent
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSource
import okio.Path
import okio.Source
import okio.buffer
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `FilePayloadSource` (05 §1 step 7, row C3a) over real files: `file:` URI decoding (escapes,
 * Unicode, authorities), the regular-file gate, the deferred stream and the counted
 * `PayloadStore` copy it feeds. Non-`file` and remote-authority URIs never reach a filesystem
 * call. The source's own filesystem work runs on the injected IO dispatcher only.
 */
class FilePayloadSourceTest {
    private lateinit var root: File
    private lateinit var dir: File
    private lateinit var payloads: FilePayloadSource

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("nd-file-payload").toFile()
        dir = File(root, "inputs").apply { mkdirs() }
        payloads = FilePayloadSource(FileSystem.SYSTEM, Dispatchers.IO)
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun fileUriOpensARegularFileWithNameAndDeclaredSize() =
        runTest {
            val file = file("feeds.opml", "<opml/>")
            val opened = open(fileUri(file))

            assertEquals("feeds.opml", opened.displayName)
            assertEquals(file.length(), opened.declaredSize)
            assertEquals("<opml/>", opened.open().buffer().readUtf8())
        }

    @Test
    fun percentEscapesDecodeToSpacesAndUnicode() =
        runTest {
            // The URI carries the escapes; the decoder yields the real on-disk name.
            val file = file("meine Feeds äß.opml", "u")
            val opened = open(fileUri(file))

            assertEquals(file.name, opened.displayName)
            assertEquals("u", opened.open().buffer().readUtf8())
        }

    @Test
    fun fileUriWithoutAnAuthorityComponentIsLocal() =
        runTest {
            // `file:/path` (single slash, no `//`) is a valid hierarchical file URI.
            val file = file("plain.opml", "p")
            assertIs<Outcome.Success<*>>(
                payloads.open(fileUri(file).replace("file:///", "file:/")),
            )
        }

    @Test
    fun localhostAuthorityIsLocal() =
        runTest {
            val file = file("local.opml", "l")
            val uri = fileUri(file).replace("file://", "file://localhost")
            val opened = open(uri)
            assertEquals("l", opened.open().buffer().readUtf8())
        }

    @Test
    fun nonFileSchemesAreRejected() =
        runTest {
            for (uri in listOf(
                "content://media/file/1",
                "https://e.example/f.opml",
                "ftp://h/f",
                "jar:file:/x!/y",
            )) {
                assertEquals(Outcome.Failure(ImportError.Unreadable), payloads.open(uri), "uri=$uri")
            }
        }

    @Test
    fun remoteAuthoritiesAreRejected() =
        runTest {
            // `file://host/` resolves as a UNC share on Windows — refused everywhere so all
            // desktop platforms agree. userinfo and ports inside the authority are out too.
            for (uri in listOf(
                "file://remotehost/share/x.opml",
                "file://user@localhost/tmp/x.opml",
                "file://localhost:8080/tmp/x.opml",
            )) {
                assertEquals(Outcome.Failure(ImportError.Unreadable), payloads.open(uri), "uri=$uri")
            }
        }

    @Test
    fun malformedAndOpaqueUrisAreRejected() =
        runTest {
            for (uri in listOf(
                "not a uri",
                "file:relative/path.opml",
                "file:///tmp/x.opml?q=1",
                "file:///tmp/x.opml#frag",
                "file:///tmp/x%zz.opml",
                "file://",
                "file:",
            )) {
                assertEquals(Outcome.Failure(ImportError.Unreadable), payloads.open(uri), "uri=$uri")
            }
        }

    @Test
    fun directoryIsRejected() =
        runTest {
            assertEquals(Outcome.Failure(ImportError.Unreadable), payloads.open(fileUri(dir)))
        }

    @Test
    fun missingFileIsRejected() =
        runTest {
            assertEquals(
                Outcome.Failure(ImportError.Unreadable),
                payloads.open(fileUri(File(dir, "gone.opml"))),
            )
        }

    @Test
    fun unreadableFileIsRejected() =
        runTest {
            // The probe open is the honest check — a mode-0 file fails it (05 §1 step 4).
            // Skipped where the test user can read regardless (root ignores permission bits).
            val file = file("locked.opml", "x")
            check(file.setReadable(false, false))
            try {
                if (!file.canRead()) {
                    assertEquals(Outcome.Failure(ImportError.Unreadable), payloads.open(fileUri(file)))
                }
            } finally {
                file.setReadable(true)
            }
        }

    @Test
    fun deviceAndSymlinksAreRejected() =
        runTest {
            // /dev/null is a character device — `isRegularFile` is false.
            assertEquals(Outcome.Failure(ImportError.Unreadable), payloads.open("file:///dev/null"))
            // 05 §1 step 7's "no link": `metadataOrNull` stats the link itself, so neither a
            // link to a directory nor a link to a regular file is a regular file.
            val file = file("real.opml", "x")
            val fileLink = dir.toPath().resolve("file-link.opml")
            Files.createSymbolicLink(fileLink, file.toPath())
            assertEquals(Outcome.Failure(ImportError.Unreadable), payloads.open(fileUri(fileLink.toFile())))
            val dirLink = dir.toPath().resolve("dir-link")
            Files.createSymbolicLink(dirLink, dir.toPath())
            assertEquals(Outcome.Failure(ImportError.Unreadable), payloads.open(fileUri(dirLink.toFile())))
        }

    @Test
    fun consumedStreamOpensDeferredInsideTheCopy() =
        runTest {
            // `open()` stats and probes once; the stream the counted copy consumes comes from
            // `Opened.open` — proven by counting `source` calls through a watching filesystem.
            val file = file("deferred.opml", "d")
            var sourceCalls = 0
            val counting =
                object : ForwardingFileSystem(FileSystem.SYSTEM) {
                    override fun source(file: Path): Source {
                        sourceCalls++
                        return super.source(file)
                    }
                }
            val watching = FilePayloadSource(counting, Dispatchers.IO)
            val opened =
                assertIs<Outcome.Success<PayloadSource.Opened>>(watching.open(fileUri(file))).value
            val afterOpen = sourceCalls
            opened.open().use { it.buffer().readUtf8() }
            assertTrue(sourceCalls > afterOpen)
        }

    @Test
    fun acquireCopiesAndTheOriginalMayBeMovedAfterwards() =
        runTest {
            // 05 §1 step 7: the counted copy is what the session owns — once `acquire`
            // returns, moving or deleting the original cannot touch the staged payload.
            val file = file("my feeds.opml", "original-bytes")
            val staged =
                assertIs<Outcome.Success<PayloadStore.Staged>>(
                    store().acquire(ImportSource(fileUri(file), displayName = null)),
                ).value
            assertEquals("my feeds.opml", staged.displayName)

            val moved = File(dir, "moved.opml")
            check(file.renameTo(moved))
            assertEquals("original-bytes", FileSystem.SYSTEM.read(staged.path) { readUtf8() })
        }

    @Test
    fun acquireRejectsAFileDeclaredOverTheCap() =
        runTest {
            // A sparse file declares > 50 MiB: the metadata hint trips `TooLarge` before a
            // byte is read (the counted copy remains the defence against a lying hint).
            val huge = File(dir, "huge.opml")
            RandomAccessFile(huge, "rw").use { it.setLength(PayloadStore.MAX_PAYLOAD_BYTES + 1) }
            assertEquals(
                Outcome.Failure(ImportError.TooLarge),
                store().acquire(ImportSource(fileUri(huge), displayName = null)),
            )
        }

    @Test
    fun acquireKeepsTheOriginalUntouched() =
        runTest {
            val file = file("keep.opml", "still-here")
            assertIs<Outcome.Success<PayloadStore.Staged>>(
                store().acquire(ImportSource(fileUri(file), displayName = null)),
            )
            assertEquals("still-here", file.readText())
        }

    // --- helpers ---------------------------------------------------------------------------------

    private fun store(): PayloadStore =
        PayloadStore(
            storagePaths = storagePathsFor(root),
            fileSystem = FileSystem.SYSTEM,
            payloads = payloads,
            httpClients = OkHttpNeutrodyneHttpClients(newNetworkClients(), testUserAgent()),
            netErrors = testClassifier,
            io = Dispatchers.Default,
        )

    private fun file(
        name: String,
        content: String,
    ): File = File(dir, name).apply { writeText(content) }

    private suspend fun open(uri: String): PayloadSource.Opened =
        assertIs<Outcome.Success<PayloadSource.Opened>>(payloads.open(uri)).value

    /** `file:///…` with proper escaping — the form dialogs and `Path.toUri` mint. */
    private fun fileUri(file: File): String = file.toPath().toUri().toASCIIString()
}
