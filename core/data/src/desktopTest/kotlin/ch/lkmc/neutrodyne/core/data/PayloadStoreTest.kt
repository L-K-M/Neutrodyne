// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.importing.PayloadStore
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.junit4.MockWebServerRule
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.junit.Rule
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `PayloadStore` (05 §1 Acquire): counted copies, the 50 MiB abort, `adopt`, `publish`/`delete`
 * and the error mapping. A `FakePayloadSource` feeds bytes; the `https://` leg runs against a
 * `MockWebServer` so the feed-client path is exercised end to end.
 */
class PayloadStoreTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    private lateinit var root: File
    private lateinit var payloads: FakePayloadSource
    private lateinit var store: PayloadStore

    @BeforeTest
    fun setUp() {
        root =
            kotlin.io.path
                .createTempDirectory("nd-payload-store")
                .toFile()
        payloads = FakePayloadSource()
        store =
            PayloadStore(
                storagePaths = storagePathsFor(root),
                fileSystem = FileSystem.SYSTEM,
                payloads = payloads,
                httpClients = OkHttpNeutrodyneHttpClients(newNetworkClients(), testUserAgent()),
                netErrors = testClassifier,
                io = Dispatchers.Default,
            )
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun copiesSourceAndLearnsDisplayName() =
        runTest {
            payloads.register(CONTENT_URI, bytes = "payload".encodeToByteArray(), displayName = "feeds.opml")

            val staged = assertIs<Outcome.Success<PayloadStore.Staged>>(acquire(CONTENT_URI)).value

            assertEquals("feeds.opml", staged.displayName)
            assertEquals("payload", FileSystem.SYSTEM.read(staged.path) { readUtf8() })
            assertTrue(staged.path.name.startsWith("incoming-"))
        }

    @Test
    fun declaredOversizeRejectsWithoutReading() =
        runTest {
            val source = payloads.register(CONTENT_URI, declaredSize = PayloadStore.MAX_PAYLOAD_BYTES + 1)

            val failure = assertIs<Outcome.Failure<ImportError>>(acquire(CONTENT_URI))

            assertEquals(ImportError.TooLarge, failure.error)
            assertEquals(0, source.openCount, "the provider stream is never opened once size is known")
        }

    @Test
    fun streamingAbortsPastTheCap() =
        runTest {
            // A declared size is absent (providers lie); the counting source must stop the copy.
            val endless = EndlessSource()
            payloads.register(CONTENT_URI, source = endless)

            val failure = assertIs<Outcome.Failure<ImportError>>(acquire(CONTENT_URI))

            assertEquals(ImportError.TooLarge, failure.error)
            assertTrue(
                endless.produced <= PayloadStore.MAX_PAYLOAD_BYTES + CHUNK,
                "the copy aborts instead of draining the provider (${endless.produced} bytes)",
            )
            assertEquals(
                emptyList(),
                importDir().let { FileSystem.SYSTEM.listOrNull(it) } ?: emptyList(),
                "a failed stage leaves no file in the import cache",
            )
        }

    @Test
    fun lyingDeclaredSizeStillBoundsTheCopy() =
        runTest {
            // The provider claims 1 byte but the stream never ends — the count decides.
            payloads.register(CONTENT_URI, declaredSize = 1, source = EndlessSource())

            val failure = assertIs<Outcome.Failure<ImportError>>(acquire(CONTENT_URI))

            assertEquals(ImportError.TooLarge, failure.error)
        }

    @Test
    fun providerOpenFailureIsUnreadable() =
        runTest {
            payloads.fail(CONTENT_URI, ImportError.Unreadable)

            val failure = assertIs<Outcome.Failure<ImportError>>(acquire(CONTENT_URI))

            assertEquals(ImportError.Unreadable, failure.error)
        }

    @Test
    fun adoptCopiesPrivateFile() =
        runTest {
            val snapshot = root.resolve("auto-snapshot.zip")
            snapshot.writeText("snapshot-bytes")

            val staged = assertIs<Outcome.Success<PayloadStore.Staged>>(store.adopt(snapshot.absolutePath.toPath())).value

            assertEquals("auto-snapshot.zip", staged.displayName)
            assertEquals("snapshot-bytes", FileSystem.SYSTEM.read(staged.path) { readUtf8() })
            // The original is untouched — the caller owns renaming it to restored-*.zip.
            assertEquals("snapshot-bytes", snapshot.readText())
        }

    @Test
    fun adoptRejectsMissingAndDirectory() =
        runTest {
            assertEquals(
                ImportError.Unreadable,
                assertIs<Outcome.Failure<ImportError>>(store.adopt(root.resolve("gone.bin").absolutePath.toPath())).error,
            )
            assertEquals(
                ImportError.Unreadable,
                assertIs<Outcome.Failure<ImportError>>(store.adopt(root.absolutePath.toPath())).error,
            )
        }

    @Test
    fun publishRenamesToSessionPathAndDeleteRemoves() =
        runTest {
            payloads.register(CONTENT_URI, bytes = "abc".encodeToByteArray())
            val staged = assertIs<Outcome.Success<PayloadStore.Staged>>(acquire(CONTENT_URI)).value

            val payloadPath = store.publish(staged, sessionId = 7)

            assertEquals("import/7.bin", payloadPath)
            val published = store.payloadFile(payloadPath)
            assertEquals("abc", FileSystem.SYSTEM.read(published) { readUtf8() })
            assertTrue(!FileSystem.SYSTEM.exists(staged.path), "the temp name is gone")

            store.delete(published)
            assertTrue(!FileSystem.SYSTEM.exists(published))
        }

    @Test
    fun httpSourceStreamsThroughFeedClient() =
        runTest {
            server.enqueue(mockResponse(body = "remote-opml"))
            val staged =
                assertIs<Outcome.Success<PayloadStore.Staged>>(
                    store.acquire(ImportSource(server.url("/list.opml").toString(), "list.opml")),
                ).value

            assertEquals("remote-opml", FileSystem.SYSTEM.read(staged.path) { readUtf8() })
            assertEquals("list.opml", staged.displayName)
        }

    @Test
    fun httpErrorMapsToNetwork() =
        runTest {
            server.enqueue(mockResponse(code = 404, body = "nope"))

            val failure =
                assertIs<Outcome.Failure<ImportError>>(
                    store.acquire(ImportSource(server.url("/missing.opml").toString(), null)),
                )

            assertEquals(ImportError.Network(NetError.Other("http_404")), failure.error)
        }

    private suspend fun acquire(uri: String): Outcome<PayloadStore.Staged, ImportError> =
        store.acquire(ImportSource(uri, "from-test"))

    private fun importDir(): Path = storagePathsFor(root).cacheDir.toPath() / PayloadStore.IMPORT_DIR

    private companion object {
        const val CONTENT_URI = "content://provider/feeds.opml"
        const val CHUNK = 16 * 1024
    }
}
