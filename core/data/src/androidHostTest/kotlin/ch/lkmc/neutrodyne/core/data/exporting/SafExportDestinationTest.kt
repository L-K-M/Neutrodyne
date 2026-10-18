// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.exporting

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.LogLevel
import ch.lkmc.neutrodyne.core.common.LogSink
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.domain.ExportError
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
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
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * `SafExportDestination` (05 Destinations, row C1): `"wt"` first, the documented `"w"` retry
 * only when the provider rejects the mode outright, typed errors instead of success, and
 * streams closed on every path. `RecordingSafProvider` scripts the provider half — the copy
 * itself runs against real local files and real descriptors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafExportDestinationTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var provider: RecordingSafProvider
    private lateinit var destination: SafExportDestination
    private lateinit var prepared: File
    private lateinit var received: File
    private var fullPfd: ParcelFileDescriptor? = null

    @Before
    fun setUp() {
        provider = Robolectric.setupContentProvider(RecordingSafProvider::class.java, AUTHORITY)
        provider.reset()
        destination =
            SafExportDestination(
                app.contentResolver,
                ExportShareUris(app, StoragePaths(app)),
                FileSystem.SYSTEM,
                Dispatchers.IO,
            )
        prepared = File(app.cacheDir, "prepared-export.bin").apply { writeBytes(PREPARED_BYTES) }
        received = File(app.cacheDir, "provider-received.bin")
    }

    @After
    fun tearDown() {
        Log.install()
        try {
            fullPfd?.close()
        } catch (_: Exception) {
            // Already closed by the adapter under test — exactly what the test asserts.
        }
        prepared.delete()
        received.delete()
    }

    @Test
    fun saveOpensWtAndCopiesEveryByte() =
        runBlocking<Unit> {
            provider.onOpen = { _, _ -> ParcelFileDescriptor.open(received, ParcelFileDescriptor.MODE_READ_WRITE) }

            val outcome = destination.save(prepared.toOkio(), destinationUri())

            assertThat(outcome).isEqualTo(Outcome.Success(Unit))
            assertThat(provider.modes).containsExactly("wt")
            assertThat(received.readBytes()).isEqualTo(PREPARED_BYTES)
            // The prepared cache file is a source, never a scratch pad.
            assertThat(prepared.readBytes()).isEqualTo(PREPARED_BYTES)
        }

    @Test
    fun saveRetriesWOnlyWhenWtIsRejected() =
        runBlocking<Unit> {
            provider.onOpen = { _, mode ->
                if (mode == "wt") throw IllegalArgumentException("unknown mode")
                ParcelFileDescriptor.open(received, ParcelFileDescriptor.MODE_READ_WRITE)
            }

            val outcome = destination.save(prepared.toOkio(), destinationUri())

            assertThat(outcome).isEqualTo(Outcome.Success(Unit))
            assertThat(provider.modes).containsExactly("wt", "w").inOrder()
            assertThat(received.readBytes()).isEqualTo(PREPARED_BYTES)
        }

    @Test
    fun saveRetriesWWhenWtRaisesNotFound() =
        runBlocking<Unit> {
            provider.onOpen = { _, mode ->
                if (mode == "wt") throw FileNotFoundException("mode unsupported")
                ParcelFileDescriptor.open(received, ParcelFileDescriptor.MODE_READ_WRITE)
            }

            val outcome = destination.save(prepared.toOkio(), destinationUri())

            assertThat(outcome).isEqualTo(Outcome.Success(Unit))
            assertThat(provider.modes).containsExactly("wt", "w").inOrder()
        }

    @Test
    fun writeFailureAfterOpenIsDestinationUnavailableAndNeverRetries() =
        runBlocking<Unit> {
            // "wt" opens fine and the write then dies with ENOSPC: the "w" fallback exists
            // only while zero bytes have gone out, so a mid-copy failure is a typed error —
            // not a retry, not a success (05 Destinations).
            provider.onOpen = { _, _ ->
                ParcelFileDescriptor
                    .open(FULL_DEVICE, ParcelFileDescriptor.MODE_WRITE_ONLY)
                    .also { fullPfd = it }
            }

            val outcome = destination.save(prepared.toOkio(), destinationUri())

            assertThat(assertIs<Outcome.Failure<ExportError>>(outcome).error)
                .isEqualTo(ExportError.DestinationUnavailable)
            assertThat(provider.modes).containsExactly("wt")
            assertThat(prepared.readBytes()).isEqualTo(PREPARED_BYTES)
            assertThat(fullPfd!!.fileDescriptor.valid()).isFalse()
        }

    @Test
    fun revokedWriteGrantIsDestinationUnavailable() =
        runBlocking<Unit> {
            provider.onOpen = { _, _ -> throw SecurityException("grant lost") }

            val outcome = destination.save(prepared.toOkio(), destinationUri())

            assertThat(assertIs<Outcome.Failure<ExportError>>(outcome).error)
                .isEqualTo(ExportError.DestinationUnavailable)
            assertThat(provider.modes).containsExactly("wt")
        }

    @Test
    fun nullProviderStreamIsDestinationUnavailable() =
        runBlocking<Unit> {
            provider.onOpenAsset = { _, _ -> null }

            val outcome = destination.save(prepared.toOkio(), destinationUri())

            assertThat(assertIs<Outcome.Failure<ExportError>>(outcome).error)
                .isEqualTo(ExportError.DestinationUnavailable)
        }

    @Test
    fun missingPreparedFileDoesNotOpenOrTruncateTheDestination() =
        runBlocking<Unit> {
            val oldBytes = "previous document".toByteArray()
            received.writeBytes(oldBytes)
            provider.onOpen = { _, _ ->
                ParcelFileDescriptor.open(
                    received,
                    ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE,
                )
            }

            val outcome = destination.save(File(app.cacheDir, "gone.bin").toOkio(), destinationUri())

            assertThat(assertIs<Outcome.Failure<ExportError>>(outcome).error).isEqualTo(ExportError.Io)
            assertThat(provider.modes).isEmpty()
            assertThat(received.readBytes()).isEqualTo(oldBytes)
        }

    @Test
    fun cancelClosesStreamsAndNeverReportsSuccess() =
        runBlocking<Unit> {
            // Deterministic mid-flight cancel: the save parks inside the provider's open,
            // the test cancels it, then lets the open answer — the copy's cancellation check
            // throws before a byte is written, `use` closes the stream, and the caller sees
            // CancellationException rather than a success for a save that never ran.
            val openCalled = CountDownLatch(1)
            val releaseOpen = CountDownLatch(1)
            received.writeBytes(ByteArray(0))
            provider.onOpen = { _, _ ->
                openCalled.countDown()
                releaseOpen.await(OPEN_GATE_SECS, TimeUnit.SECONDS)
                ParcelFileDescriptor.open(received, ParcelFileDescriptor.MODE_READ_WRITE)
            }

            val deferred =
                async(Dispatchers.IO) { destination.save(prepared.toOkio(), destinationUri()) }
            assertThat(openCalled.await(OPEN_GATE_SECS, TimeUnit.SECONDS)).isTrue()
            deferred.cancel()
            releaseOpen.countDown()

            assertFailsWith<CancellationException> { deferred.await() }
            assertThat(provider.lastPfd!!.fileDescriptor.valid()).isFalse()
            assertThat(provider.modes).containsExactly("wt")
            assertThat(received.length()).isEqualTo(0L)
        }

    @Test
    fun failedCopyDeletesTheFreshDocument() =
        runBlocking<Unit> {
            prepared.writeBytes(LARGE_BYTES)
            received.writeBytes(ByteArray(0))
            provider.onOpen = { _, _ ->
                ParcelFileDescriptor.open(received, ParcelFileDescriptor.MODE_READ_WRITE)
            }
            provider.onDelete = {
                assertThat(received.length()).isGreaterThan(0L)
                assertThat(provider.lastPfd!!.fileDescriptor.valid()).isFalse()
                check(received.delete())
            }
            val adapter = destinationWith(scriptedReads { n -> if (n == 2) throw IOException("cache read failed") })

            val outcome = adapter.save(prepared.toOkio(), destinationUri())

            assertThat(assertIs<Outcome.Failure<ExportError>>(outcome).error).isEqualTo(ExportError.Io)
            assertThat(provider.deleted).containsExactly(Uri.parse(destinationUri()))
            assertThat(received.exists()).isFalse()
        }

    @Test
    fun cancelledCopyDeletesTheFreshDocument() =
        runBlocking<Unit> {
            prepared.writeBytes(LARGE_BYTES)
            received.writeBytes(ByteArray(0))
            provider.onOpen = { _, _ ->
                ParcelFileDescriptor.open(received, ParcelFileDescriptor.MODE_READ_WRITE)
            }
            provider.onDelete = {
                assertThat(received.length()).isGreaterThan(0L)
                assertThat(provider.lastPfd!!.fileDescriptor.valid()).isFalse()
                check(received.delete())
            }
            lateinit var savingJob: Job
            val adapter = destinationWith(scriptedReads { n -> if (n == 2) savingJob.cancel() })
            val save =
                async(Dispatchers.IO) {
                    savingJob = coroutineContext[Job]!!
                    adapter.save(prepared.toOkio(), destinationUri())
                }

            save.join()
            assertFailsWith<CancellationException> { save.await() }
            assertThat(provider.deleted).containsExactly(Uri.parse(destinationUri()))
            assertThat(received.exists()).isFalse()
        }

    @Test(timeout = 10_000)
    fun failedDeletePreservesDestinationUnavailableAndLogsNoPaths() =
        runBlocking<Unit> {
            prepared.writeBytes(LARGE_BYTES)
            received.writeBytes(ByteArray(0))
            provider.onOpen = { _, _ ->
                ParcelFileDescriptor.open(received, ParcelFileDescriptor.MODE_READ_WRITE)
            }
            val warnings = mutableListOf<String>()
            Log.install(LogSink { level, _, message -> if (level == LogLevel.WARN) warnings += message })
            provider.onDelete = { throw SecurityException("$it ${prepared.absolutePath}") }
            val adapter = destinationWith(scriptedReads { n -> if (n == 2) provider.lastPfd!!.close() })

            val outcome = adapter.save(prepared.toOkio(), destinationUri())

            assertThat(assertIs<Outcome.Failure<ExportError>>(outcome).error)
                .isEqualTo(ExportError.DestinationUnavailable)
            assertThat(provider.deleted).containsExactly(Uri.parse(destinationUri()))
            assertThat(received.length()).isGreaterThan(0L)
            assertThat(warnings).containsExactly("Partial export document cleanup failed: SecurityException")
        }

    @Test(timeout = 10_000)
    fun cancelledCopySurfacesAFailedDelete() =
        runBlocking<Unit> {
            // A cancelled save throws instead of returning, so a refused delete rides the
            // CancellationException as a suppressed failure rather than vanishing silently.
            prepared.writeBytes(LARGE_BYTES)
            received.writeBytes(ByteArray(0))
            provider.onOpen = { _, _ ->
                ParcelFileDescriptor.open(received, ParcelFileDescriptor.MODE_READ_WRITE)
            }
            provider.onDelete = { throw SecurityException("provider refused delete") }
            lateinit var savingJob: Job
            val cancellation = CancellationException("Copy cancelled")
            val adapter =
                destinationWith(
                    scriptedReads { n ->
                        if (n == 2) {
                            savingJob.cancel(cancellation)
                            throw cancellation
                        }
                    },
                )
            val save =
                async(Dispatchers.IO) {
                    savingJob = coroutineContext[Job]!!
                    adapter.save(prepared.toOkio(), destinationUri())
                }

            save.join()
            assertFailsWith<CancellationException> { save.await() }
            // Coroutine stack recovery can copy exceptions, so inspect the original copy failure.
            assertThat(cancellation.suppressed.map { it::class.simpleName }).containsExactly("SecurityException")
            assertThat(provider.deleted).containsExactly(Uri.parse(destinationUri()))
            assertThat(received.length()).isGreaterThan(0L)
        }

    /** The adapter under test with a scripted prepared-file [FileSystem]. */
    private fun destinationWith(fileSystem: FileSystem) =
        SafExportDestination(
            app.contentResolver,
            ExportShareUris(app, StoragePaths(app)),
            fileSystem,
            Dispatchers.IO,
        )

    /**
     * A `FileSystem` that hands [onRead] the 1-based number of each prepared-file read, so a
     * test can fail or cancel mid-copy after at least one chunk reached the document.
     */
    private fun scriptedReads(onRead: (Int) -> Unit): FileSystem =
        object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun source(file: Path): Source {
                val delegate = super.source(file)
                var reads = 0
                return object : ForwardingSource(delegate) {
                    override fun read(
                        sink: Buffer,
                        byteCount: Long,
                    ): Long {
                        val n = super.read(sink, byteCount)
                        onRead(++reads)
                        return n
                    }
                }
            }
        }

    private fun destinationUri(): String = "content://$AUTHORITY/doc"

    private fun File.toOkio() = absolutePath.toPath()

    private companion object {
        const val AUTHORITY = "ch.lkmc.neutrodyne.core.data.test.exports"
        val PREPARED_BYTES = "opml-export-bytes".toByteArray()

        /** Multi-chunk payload: the scripted read can stop after bytes reached the document. */
        val LARGE_BYTES = ByteArray(128 * 1024) { (it % 251).toByte() }

        /** How long the scripted provider open may park before the test would deadlock. */
        const val OPEN_GATE_SECS = 10L

        /** Opens fine for write, fails every write with ENOSPC — the deterministic write failure. */
        val FULL_DEVICE = File("/dev/full")
    }
}

/**
 * The scripted write provider for [SafExportDestinationTest]: records every requested mode in
 * [modes] so tests can pin the `"wt"`/`"w"` sequence, and scripts `openFile`/`openAssetFile`
 * answers — success, mode rejection, grants and the null-stream leg.
 */
class RecordingSafProvider : ContentProvider() {
    val modes = mutableListOf<String>()
    var onOpen: (Uri, String) -> ParcelFileDescriptor = { uri, _ -> throw FileNotFoundException(uri.toString()) }
    var onOpenAsset: ((Uri, String) -> AssetFileDescriptor?)? = null

    /** URIs `DocumentsContract.deleteDocument` asked the provider to delete. */
    val deleted = mutableListOf<Uri>()
    var onDelete: (Uri) -> Unit = { }

    var lastPfd: ParcelFileDescriptor? = null
        private set

    fun reset() {
        modes.clear()
        onOpen = { uri, _ -> throw FileNotFoundException(uri.toString()) }
        onOpenAsset = null
        deleted.clear()
        onDelete = { }
        lastPfd = null
    }

    override fun onCreate(): Boolean = true

    /** `deleteDocument` reaches the provider as `call("android:deleteDocument")` with `extras["uri"]`. */
    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        if (method == DELETE_DOCUMENT_METHOD) {
            val target = checkNotNull(extras?.getParcelable("uri", Uri::class.java))
            deleted += target
            onDelete(target)
            return Bundle()
        }
        return super.call(method, arg, extras)
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        modes += mode
        return onOpen(uri, mode).also { lastPfd = it }
    }

    /** `openOutputStream` resolves through here; a scripted `null` is the no-stream answer. */
    override fun openAssetFile(
        uri: Uri,
        mode: String,
    ): AssetFileDescriptor? = onOpenAsset?.invoke(uri, mode) ?: super.openAssetFile(uri, mode)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

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

    private companion object {
        /** The call `DocumentsContract.deleteDocument` sends (not a public SDK constant). */
        const val DELETE_DOCUMENT_METHOD = "android:deleteDocument"
    }
}
