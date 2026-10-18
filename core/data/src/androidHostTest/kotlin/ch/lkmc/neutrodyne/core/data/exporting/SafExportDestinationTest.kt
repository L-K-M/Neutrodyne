// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.exporting

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.domain.ExportError
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    fun missingPreparedFileIsIoAndClosesTheStream() =
        runBlocking<Unit> {
            provider.onOpen = { _, _ -> ParcelFileDescriptor.open(received, ParcelFileDescriptor.MODE_READ_WRITE) }

            val outcome = destination.save(File(app.cacheDir, "gone.bin").toOkio(), destinationUri())

            assertThat(assertIs<Outcome.Failure<ExportError>>(outcome).error).isEqualTo(ExportError.Io)
            assertThat(provider.lastPfd!!.fileDescriptor.valid()).isFalse()
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

    private fun destinationUri(): String = "content://$AUTHORITY/doc"

    private fun File.toOkio() = absolutePath.toPath()

    private companion object {
        const val AUTHORITY = "ch.lkmc.neutrodyne.core.data.test.exports"
        val PREPARED_BYTES = "opml-export-bytes".toByteArray()

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

    var lastPfd: ParcelFileDescriptor? = null
        private set

    fun reset() {
        modes.clear()
        onOpen = { uri, _ -> throw FileNotFoundException(uri.toString()) }
        onOpenAsset = null
        lastPfd = null
    }

    override fun onCreate(): Boolean = true

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
}
