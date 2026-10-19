// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.importing

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.common.UserAgentProvider
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.network.NetErrorClassifier
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.network.okhttp.AuthInterceptor
import ch.lkmc.neutrodyne.core.network.okhttp.CoreClients
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import ch.lkmc.neutrodyne.core.network.okhttp.JvmNetErrors
import ch.lkmc.neutrodyne.core.network.okhttp.LocalNetworkGuard
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import ch.lkmc.neutrodyne.core.network.okhttp.UserAgentInterceptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * `ContentPayloadSource` (05 §1 step 1, row C1): metadata may be absent or lie, grants may be
 * revoked mid-flight, and the deferred `open` must hand `PayloadStore` a fresh stream — the
 * counted-acquisition legs run through a real `PayloadStore` over a real client stack, only
 * the provider is scripted (host tests prove adapter behavior, not third-party transport).
 *
 * `ScriptedProvider` stands in for the SAF/document provider: `query`/`openFile`/
 * `openAssetFile` answers are assigned per test. `file://` legs use real local files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContentPayloadSourceTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var provider: ScriptedProvider
    private lateinit var source: ContentPayloadSource
    private lateinit var clients: OkHttpNeutrodyneHttpClients
    private lateinit var store: PayloadStore

    @Before
    fun setUp() {
        provider = Robolectric.setupContentProvider(ScriptedProvider::class.java, AUTHORITY)
        provider.reset()
        source = ContentPayloadSource(app.contentResolver, Dispatchers.IO)
        clients = testClients()
        store =
            PayloadStore(
                storagePaths = StoragePaths(app),
                fileSystem = FileSystem.SYSTEM,
                payloads = source,
                httpClients = clients,
                netErrors = TEST_CLASSIFIER,
                io = Dispatchers.IO,
            )
    }

    @After
    fun tearDown() {
        clients.close()
        File(app.cacheDir, PayloadStore.IMPORT_DIR).deleteRecursively()
    }

    // --- Opened contract: metadata -------------------------------------------------------

    @Test
    fun contentUriCarriesDeclaredNameAndSize() =
        runBlocking<Unit> {
            val backing = backingFile("payload-bytes")
            provider.onQuery = { cursor("feeds.opml", 100L) }
            provider.onOpen = { _, _ -> ParcelFileDescriptor.open(backing, ParcelFileDescriptor.MODE_READ_ONLY) }

            val opened = assertSuccess(source.open(contentUri("doc")))

            assertThat(opened.displayName).isEqualTo("feeds.opml")
            assertThat(opened.declaredSize).isEqualTo(100L)
            assertThat(readAll(opened)).isEqualTo("payload-bytes")
        }

    @Test
    fun absentMetadataStillOpens() =
        runBlocking<Unit> {
            val backing = backingFile("no-meta")
            provider.onQuery = { null }
            provider.onOpen = { _, _ -> ParcelFileDescriptor.open(backing, ParcelFileDescriptor.MODE_READ_ONLY) }

            val opened = assertSuccess(source.open(contentUri("doc")))

            assertThat(opened.displayName).isNull()
            assertThat(opened.declaredSize).isNull()
            assertThat(readAll(opened)).isEqualTo("no-meta")
        }

    @Test
    fun lyingDeclaredSizePassesThroughUntouched() =
        runBlocking<Unit> {
            // The provider claims 3 bytes for a 100-byte file; the hint is reported verbatim —
            // only PayloadStore's counted copy is authoritative (05 §1).
            val backing = backingFile(ByteArray(100) { 'x'.code.toByte() })
            provider.onQuery = { cursor("lying.opml", 3L) }
            provider.onOpen = { _, _ -> ParcelFileDescriptor.open(backing, ParcelFileDescriptor.MODE_READ_ONLY) }

            val opened = assertSuccess(source.open(contentUri("doc")))

            assertThat(opened.declaredSize).isEqualTo(3L)
            assertThat(readAll(opened)).hasLength(100)
        }

    // --- Opened contract: failures -------------------------------------------------------

    @Test
    fun deniedQueryIsUnreadable() =
        runBlocking<Unit> {
            provider.onQuery = { throw SecurityException("grant revoked") }

            assertThat(assertFailure(source.open(contentUri("doc")))).isEqualTo(ImportError.Unreadable)
        }

    @Test
    fun providerQueryCrashStillOpensWithoutMetadata() =
        runBlocking<Unit> {
            // A misbehaving provider that throws on query may still stream — a non-Security
            // exception degrades metadata, it does not fail the acquire (05 §1 step 1).
            val backing = backingFile("still-readable")
            provider.onQuery = { throw IllegalStateException("provider bug") }
            provider.onOpen = { _, _ -> ParcelFileDescriptor.open(backing, ParcelFileDescriptor.MODE_READ_ONLY) }

            val opened = assertSuccess(source.open(contentUri("doc")))

            assertThat(opened.displayName).isNull()
            assertThat(readAll(opened)).isEqualTo("still-readable")
        }

    @Test
    fun revokedGrantFailsInsideDeferredOpen() =
        runBlocking<Unit> {
            // The grant check succeeds at acquire time but dies before the stream opens —
            // the failure surfaces from `open()`, which the store maps to Unreadable.
            provider.onQuery = { cursor("revoked.opml", 10L) }
            provider.onOpen = { _, _ -> throw SecurityException("grant lost") }

            val opened = assertSuccess(source.open(contentUri("doc")))

            assertFailsWith<SecurityException> { opened.open() }
        }

    @Test
    fun nullProviderStreamThrowsOnOpen() =
        runBlocking<Unit> {
            // `openInputStream` resolves through `openAssetFile`; a null answer is the same
            // "couldn't open" as a thrown FileNotFoundException (05 §1 step 4).
            provider.onQuery = { null }
            provider.onOpenAsset = { _, _ -> null }

            val opened = assertSuccess(source.open(contentUri("doc")))

            assertFailsWith<FileNotFoundException> { opened.open() }
        }

    @Test
    fun openFailureThrowsOnOpen() =
        runBlocking<Unit> {
            provider.onQuery = { null }
            provider.onOpen = { _, _ -> throw FileNotFoundException("gone") }

            val opened = assertSuccess(source.open(contentUri("doc")))

            assertFailsWith<FileNotFoundException> { opened.open() }
        }

    @Test
    fun unsupportedSchemesAreUnreadable() =
        runBlocking<Unit> {
            for (uri in listOf("garbage", "http://example.com/x", "ftp://example.com/x")) {
                assertThat(assertFailure(source.open(uri))).isEqualTo(ImportError.Unreadable)
            }
        }

    // --- file:// (best effort, 05 §1) ----------------------------------------------------

    @Test
    fun fileUriStreamsARealFile() =
        runBlocking<Unit> {
            val backing = backingFile("file-bytes")

            val opened = assertSuccess(source.open(Uri.fromFile(backing).toString()))

            assertThat(opened.displayName).isEqualTo(backing.name)
            assertThat(opened.declaredSize).isEqualTo(backing.length())
            assertThat(readAll(opened)).isEqualTo("file-bytes")
        }

    @Test
    fun fileUriRejectsMissingAndNonRegular() =
        runBlocking<Unit> {
            val missing = File(app.cacheDir, "never-existed.bin")
            assertThat(assertFailure(source.open(Uri.fromFile(missing).toString())))
                .isEqualTo(ImportError.Unreadable)
            assertThat(assertFailure(source.open(Uri.fromFile(app.cacheDir).toString())))
                .isEqualTo(ImportError.Unreadable)
        }

    // --- Through PayloadStore: counted acquisition + descriptor lifecycle ----------------

    @Test
    fun acquireStagesCountedBytesAndClosesDescriptor() =
        runBlocking<Unit> {
            val backing = backingFile("staged-bytes")
            provider.onQuery = { cursor("feeds.opml", backing.length()) }
            provider.onOpen = { _, _ -> ParcelFileDescriptor.open(backing, ParcelFileDescriptor.MODE_READ_ONLY) }

            val staged =
                assertIs<Outcome.Success<PayloadStore.Staged>>(
                    store.acquire(ImportSource(contentUri("doc"), "fallback")),
                ).value

            assertThat(staged.displayName).isEqualTo("feeds.opml")
            assertThat(FileSystem.SYSTEM.read(staged.path) { readUtf8() }).isEqualTo("staged-bytes")
            assertThat(provider.lastPfd!!.fileDescriptor.valid()).isFalse()
        }

    @Test
    fun acquireCopiesEverythingALyingSizeHides() =
        runBlocking<Unit> {
            val backing = backingFile(ByteArray(200) { 'y'.code.toByte() })
            provider.onQuery = { cursor("small.opml", 1L) }
            provider.onOpen = { _, _ -> ParcelFileDescriptor.open(backing, ParcelFileDescriptor.MODE_READ_ONLY) }

            val staged =
                assertIs<Outcome.Success<PayloadStore.Staged>>(
                    store.acquire(ImportSource(contentUri("doc"), null)),
                ).value

            assertThat(FileSystem.SYSTEM.metadata(staged.path).size).isEqualTo(200L)
        }

    @Test
    fun acquireRejectsOversizeBeforeOpeningTheStream() =
        runBlocking<Unit> {
            provider.onQuery = { cursor("huge.opml", PayloadStore.MAX_PAYLOAD_BYTES + 1) }
            provider.onOpen = { _, _ -> error("the provider stream must never open") }

            val failure =
                assertIs<Outcome.Failure<ImportError>>(
                    store.acquire(ImportSource(contentUri("doc"), "huge.opml")),
                )

            assertThat(failure.error).isEqualTo(ImportError.TooLarge)
            assertThat(provider.openCount).isEqualTo(0)
        }

    @Test
    fun acquireMapsProviderOpenFailureToUnreadable() =
        runBlocking<Unit> {
            provider.onQuery = { null }
            provider.onOpen = { _, _ -> throw SecurityException("grant lost") }

            val failure =
                assertIs<Outcome.Failure<ImportError>>(
                    store.acquire(ImportSource(contentUri("doc"), null)),
                )

            assertThat(failure.error).isEqualTo(ImportError.Unreadable)
        }

    // --- helpers -------------------------------------------------------------------------

    private fun contentUri(path: String): String = "content://$AUTHORITY/$path"

    private fun backingFile(contents: String): File = backingFile(contents.toByteArray())

    private fun backingFile(contents: ByteArray): File =
        File(app.cacheDir, "provider-backing-${backingCounter++}.bin").apply { writeBytes(contents) }

    private var backingCounter = 0

    private fun cursor(
        name: String,
        size: Long,
    ): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any?>(name, size))
        }

    private fun readAll(opened: PayloadSource.Opened): String = opened.open().use { it.buffer().readUtf8() }

    private fun assertSuccess(outcome: Outcome<PayloadSource.Opened, ImportError>): PayloadSource.Opened =
        assertIs<Outcome.Success<PayloadSource.Opened>>(outcome).value

    private fun assertFailure(outcome: Outcome<PayloadSource.Opened, ImportError>): ImportError =
        assertIs<Outcome.Failure<ImportError>>(outcome).error

    private companion object {
        const val AUTHORITY = "ch.lkmc.neutrodyne.core.data.test.payloads"

        val TEST_CLASSIFIER =
            object : NetErrorClassifier {
                override fun classify(e: Throwable): NetError = JvmNetErrors.classify(e, connected = true)
            }

        /** The androidHostTest mirror of `desktopTest/FetchTestSupport`'s client stack. */
        val TEST_PLATFORM =
            object : PlatformInfo {
                override val kind = PlatformKind.ANDROID
                override val userAgentPlatform = "Android 34; test"
                override val androidSdkInt = 34
                override val regionCode = "DE"
            }

        fun testClients(): OkHttpNeutrodyneHttpClients {
            val userAgent = UserAgentProvider(TEST_PLATFORM, "0.1.0", "https://example.com/repo")
            return OkHttpNeutrodyneHttpClients(
                NetworkClients(
                    CoreClients(
                        ua = UserAgentInterceptor(userAgent),
                        lanGuard = LocalNetworkGuard(LocalNetworkAccess(), TEST_PLATFORM),
                        hints = DnsFamilyHints(),
                        debugInterceptors = emptySet(),
                    ),
                    AuthInterceptor { CredentialLookup.None },
                ),
                userAgent,
            )
        }
    }
}

/**
 * The scripted provider behind every `content://` URI in [ContentPayloadSourceTest]: per-URI
 * answers for `query` (the `OpenableColumns` row), `openFile` (the read stream's descriptor)
 * and `openAssetFile` (the null-stream leg `openInputStream` resolves through). [lastPfd]
 * keeps the descriptor for the closed-on-consume assertion.
 */
class ScriptedProvider : ContentProvider() {
    var onQuery: (Uri) -> Cursor? = { null }
    var onOpen: (Uri, String) -> ParcelFileDescriptor = { uri, _ -> throw FileNotFoundException(uri.toString()) }
    var onOpenAsset: ((Uri, String) -> AssetFileDescriptor?)? = null

    var openCount = 0
        private set
    var lastPfd: ParcelFileDescriptor? = null
        private set

    fun reset() {
        onQuery = { null }
        onOpen = { uri, _ -> throw FileNotFoundException(uri.toString()) }
        onOpenAsset = null
        openCount = 0
        lastPfd = null
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = onQuery(uri)

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        openCount++
        return onOpen(uri, mode).also { lastPfd = it }
    }

    /** `openInputStream` resolves through here; a scripted `null` is the no-stream answer. */
    override fun openAssetFile(
        uri: Uri,
        mode: String,
    ): AssetFileDescriptor? {
        // A scripted handler's `null` must reach the resolver, not fall back to `openFile`.
        val handler = onOpenAsset ?: return super.openAssetFile(uri, mode)
        return handler(uri, mode)
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun openFile(
        uri: Uri,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor = openFile(uri, mode)
}
