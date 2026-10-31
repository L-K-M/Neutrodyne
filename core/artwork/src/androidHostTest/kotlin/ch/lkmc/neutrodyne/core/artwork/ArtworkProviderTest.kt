// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.LogSink
import ch.lkmc.neutrodyne.core.common.art.Monogram
import ch.lkmc.neutrodyne.core.database.ArtworkEntity
import ch.lkmc.neutrodyne.core.database.DatabaseFactory
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import coil3.BitmapImage
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ArtworkTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkProviderTest {
    private val app = RuntimeEnvironment.getApplication() as ArtworkTestApplication
    private lateinit var fixture: ArtworkHostFixture
    private lateinit var provider: ArtworkProvider
    private val key = "u-" + "a".repeat(40)
    private val url = "https://synthetic.test/cover.jpg"

    @Before fun setUp() {
        fixture = ArtworkHostFixture(app)
        // Load Robolectric's native graphics before the provider's real two-second budget.
        val warm = fixture.monograms.render(Monogram.spec("Warm up")) as BitmapImage
        fixture.codec.encode(warm)
        warm.bitmap.recycle()
        Log.install(
            LogSink { _, _, message ->
                println(message)
            },
        )
        app.graph =
            object : ArtworkProviderGraph {
                override val artworkProviderAccess = Provider { fixture.access }
            }
        provider =
            ArtworkProvider().apply {
                attachInfo(
                    app,
                    ProviderInfo().apply {
                        authority = "${app.packageName}.artwork"
                        exported = true
                    },
                )
            }
    }

    @After fun tearDown() {
        fixture.close()
        Log.install()
    }

    @Test fun creationDoesNotResolveGraphOrOpenDatabase() {
        assertTrue(provider.onCreate())
        assertEquals(DatabaseOpenState.Pending, fixture.opener.openState.value)
    }

    @Test fun rejectsEveryWriteEntryPointAndTraversal() {
        for (mode in listOf("w", "rw", "rwt", "wa")) {
            assertFailsWith<SecurityException> { provider.openFile(uri(), mode) }
        }
        for (path in listOf("../$key", "%2e%2e%2f$key", "$key/extra", "", "$key%2f..", "u-abc")) {
            val bad = Uri.parse("content://${app.packageName}.artwork/$path")
            assertFailsWith<FileNotFoundException> { provider.openFile(bad, "r") }
            assertNull(provider.query(bad, null, null, null, null))
        }
        assertFailsWith<FileNotFoundException> { provider.openFile(Uri.parse("content://wrong.artwork/$key"), "r") }
        assertFailsWith<UnsupportedOperationException> { provider.insert(uri(), ContentValues()) }
        assertFailsWith<UnsupportedOperationException> { provider.update(uri(), ContentValues(), null, null) }
        assertFailsWith<UnsupportedOperationException> { provider.delete(uri(), null, null) }
        assertFailsWith<UnsupportedOperationException> { provider.call("anything", null, null) }
    }

    @Test fun fallbackPreservesRetryMetadataAndIsSkippedByMapper() =
        runBlocking {
            fixture.open()
            fixture.podcast(key, url)
            fixture.db.artworkDao().upsert(
                ArtworkEntity(key, url, fetchedAt = 1234L, lastError = "TOO_SMALL:3", version = 2),
            )
            assertPng(provider.openFile(uri(), "r"))
            val row = assertNotNull(fixture.db.artworkDao().byKey(key))
            assertEquals("$key.fallback.png", row.localPath)
            assertEquals("TOO_SMALL:3", row.lastError)
            assertEquals(1234L, row.fetchedAt)
            assertEquals(3, row.version)
            assertEquals(0, fixture.store.collectGarbage())
            assertNotNull(fixture.store.pinnedFile(key))
            assertEquals("image/png", provider.getType(uri()))
            assertEquals(
                url,
                ArtworkRefMapper(
                    fixture.store,
                ).map(
                    ch.lkmc.neutrodyne.core.model
                        .ArtworkRef(key, url, row.version),
                    coil3.request.Options(app),
                ),
            )
            val columns = arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME)
            provider.query(uri(), columns, null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getLong(0) > 0)
                assertEquals("$key.fallback.png", cursor.getString(1))
                assertFalse(cursor.moveToNext())
            }
        }

    @Test fun monogramUsesNormalPngAndUnknownKeyUsesNeutralWithoutAddingRow() =
        runBlocking {
            fixture.open()
            val monogram = "m-" + "b".repeat(40)
            fixture.podcast(monogram)
            assertPng(provider.openFile(uri(monogram), "r"))
            assertEquals(
                "$monogram.png",
                fixture.db
                    .artworkDao()
                    .byKey(monogram)
                    ?.localPath,
            )
            assertPng(provider.openFile(uri(), "r"))
            assertNull(fixture.db.artworkDao().byKey(key))
            assertEquals(0, fixture.enqueues)
        }

    @Test fun neutralMetadataMatchesTheReturnedImage() =
        runBlocking {
            fixture.open()
            val options = BitmapFactory.Options()
            ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri(), "r")).use { stream ->
                assertNotNull(BitmapFactory.decodeStream(stream, null, options)).recycle()
            }
            assertEquals("image/png", options.outMimeType)
            assertEquals(options.outMimeType, provider.getType(uri()))
            provider.query(uri(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("$key.png", cursor.getString(0))
            }
            assertEquals(0, fixture.enqueues)
        }

    @Test fun providerAndRunnerSameKeyPublishOneConsistentFile() =
        runBlocking {
            fixture.open()
            fixture.podcast(key, url)
            val reached = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565).apply { eraseColor(0xff194b7c.toInt()) }
            val image = fixture.codec.encode(bitmap.asImage())
            val loader =
                coil3.ImageLoader
                    .Builder(app)
                    .interceptorCoroutineContext(Dispatchers.IO)
                    .components {
                        add(
                            object : Interceptor {
                                override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                                    reached.complete(Unit)
                                    release.await()
                                    return SuccessResult(bitmap.asImage(), chain.request, DataSource.NETWORK)
                                }
                            },
                        )
                    }.build()
            try {
                val runner =
                    ArtworkSyncRunner(
                        fixture.db,
                        fixture.store,
                        fixture.codec,
                        fixture.monograms,
                        Provider { loader },
                        app,
                        fixture.scheduler,
                        fixture.clock,
                        Dispatchers.IO,
                    )
                val running = async(Dispatchers.IO) { runner.runOnce() }
                kotlinx.coroutines.withTimeout(10_000) { reached.await() }
                // The provider wins the fallback publication while the runner is fetching this key.
                assertPng(provider.openFile(uri(), "r"))
                assertEquals(
                    "$key.fallback.png",
                    fixture.db
                        .artworkDao()
                        .byKey(key)
                        ?.localPath,
                )
                release.complete(Unit)
                kotlinx.coroutines.withTimeout(10_000) { running.await() }
                // A later provider call must open the fetched file rather than publishing a stale fallback.
                provider.openFile(uri(), "r").use { assertTrue(it.statSize > 0) }
                val row = assertNotNull(fixture.db.artworkDao().byKey(key))
                assertEquals(url, row.url)
                assertEquals("$key.jpg", row.localPath)
                val storedBytes = File(app.filesDir, "artwork/$key.jpg").readBytes().toList()
                assertEquals(image.bytes.toByteArray().toList(), storedBytes)
                assertFalse(File(app.filesDir, "artwork/$key.fallback.png").exists())
                assertFalse(File(app.filesDir, "artwork/$key.tmp").exists())
            } finally {
                release.complete(Unit)
                loader.shutdown()
                bitmap.recycle()
            }
        }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun pendingDatabaseUsesNeutralAfterTheBound() =
        runBlocking<Unit> {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val factory = fixture.factory
            fixture.close()
            fixture =
                ArtworkHostFixture(
                    app,
                    object : DatabaseFactory by factory {
                        override fun exists(): Boolean {
                            started.countDown()
                            check(release.await(30, TimeUnit.SECONDS))
                            return false
                        }
                    },
                )
            val time = kotlinx.coroutines.test.TestCoroutineScheduler()
            val access =
                ArtworkProviderAccess(
                    fixture.opener,
                    Provider { fixture.store },
                    fixture.codec,
                    fixture.monograms,
                    kotlinx.coroutines.test.StandardTestDispatcher(time),
                )
            app.graph =
                object : ArtworkProviderGraph {
                    override val artworkProviderAccess = Provider { access }
                }
            val opening = async(Dispatchers.IO) { fixture.open() }
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS))
                val returned = async(Dispatchers.IO) { provider.openFile(uri(), "r") }
                // Advance only the database timeout; encoding/decoding stays on real IO.
                kotlinx.coroutines.withTimeout(10_000) {
                    while (!returned.isCompleted) {
                        time.advanceUntilIdle()
                        kotlinx.coroutines.delay(1)
                    }
                }
                assertEquals(2_000L, time.currentTime)
                assertEquals(DatabaseOpenState.Pending, fixture.opener.openState.value)
                assertPng(returned.await())
            } finally {
                release.countDown()
                opening.await()
            }
        }

    @Test fun pinnedFileAndVersionQueryWorkWithoutNetwork() =
        runBlocking {
            fixture.open()
            fixture.podcast(key, url)
            val image = fixture.codec.encode(fixture.monograms.render(Monogram.spec("Offline")))
            fixture.store.applyBatch(listOf(ArtworkProduction(key, url, image)), fixture.clock.now())
            val versioned = uri().buildUpon().appendQueryParameter("v", "999").build()
            provider.openFile(versioned, "r").use { assertEquals(image.bytes.size.toLong(), it.statSize) }
            assertEquals("image/jpeg", provider.getType(uri()))
            assertEquals(0, fixture.enqueues)
        }

    @Test fun pinnedAndMissingFileReadsNeverFetchTheirRemoteUrl() =
        runBlocking {
            val server = MockWebServer()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) =
                        MockResponse(code = HttpURLConnection.HTTP_NO_CONTENT)
                }
            server.start()
            try {
                val control =
                    (server.url("/control").toUrl().openConnection() as HttpURLConnection).apply {
                        connectTimeout = HTTP_CONTROL_TIMEOUT_MS
                        readTimeout = HTTP_CONTROL_TIMEOUT_MS
                    }
                try {
                    assertEquals(HttpURLConnection.HTTP_NO_CONTENT, control.responseCode)
                } finally {
                    control.disconnect()
                }
                assertEquals(1, server.requestCount, "The HTTP tripwire must observe a real request")
                val before = server.requestCount

                // Isolate the provider: no UI, audio stream or artwork worker shares this server.
                fixture.open()
                val remote = server.url("/cover.jpg").toString()
                fixture.podcast(key, remote)
                val image = fixture.codec.encode(fixture.monograms.render(Monogram.spec("Offline")))
                fixture.store.applyBatch(listOf(ArtworkProduction(key, remote, image)), fixture.clock.now())
                provider.openFile(uri(), "r").use { assertEquals(image.bytes.size.toLong(), it.statSize) }
                assertTrue(assertNotNull(fixture.store.pinnedFile(key)).toFile().delete())
                assertPng(provider.openFile(uri(), "r"))
                assertEquals(
                    "$key.fallback.png",
                    fixture.db
                        .artworkDao()
                        .byKey(key)
                        ?.localPath,
                )
                assertEquals(before, server.requestCount, "Provider reads must not fetch the remote cover")
                assertEquals(0, fixture.enqueues)
            } finally {
                server.close()
            }
        }

    private fun uri(value: String = key): Uri = Uri.parse("content://${app.packageName}.artwork/$value?v=1")

    private fun assertPng(descriptor: ParcelFileDescriptor) {
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            val bitmap = assertNotNull(BitmapFactory.decodeStream(stream))
            assertEquals(512, bitmap.width)
            assertEquals(512, bitmap.height)
            bitmap.recycle()
        }
    }

    private companion object {
        const val HTTP_CONTROL_TIMEOUT_MS = 5_000
    }
}
