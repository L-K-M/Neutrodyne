// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.size.Size
import coil3.toBitmap
import coil3.transform.Transformation
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import okio.Path
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Real Coil, the app IMAGE client, disk cache and native pixels; only host routing is test-only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class YouTubeThumbnailInterceptorTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val app: Application = RuntimeEnvironment.getApplication()
    private val store = ThumbnailStore()
    private lateinit var loader: ImageLoader
    private lateinit var client: OkHttpClient
    private lateinit var directory: File

    @Before fun setup() {
        directory = File(app.cacheDir, "thumbnail-${System.nanoTime()}").apply { mkdirs() }
        client =
            artworkTestImageClient()
                .newBuilder()
                .addInterceptor { chain ->
                    val url = chain.request().url
                    check(url.host == "i.ytimg.com")
                    chain.proceed(
                        chain
                            .request()
                            .newBuilder()
                            .url(serverRule.server.url(url.encodedPath))
                            .build(),
                    )
                }.build()
        loader =
            ImageLoader
                .Builder(app)
                .components {
                    add(ArtworkRefMapper(store))
                    add(YouTubeThumbnailInterceptor(store))
                    add(TinyImageInterceptor())
                    add(OkHttpNetworkFetcherFactory(callFactory = { client }))
                }.diskCache {
                    DiskCache
                        .Builder()
                        .directory(directory.resolve("disk").path.toPath())
                        .maxSizeBytes(10L * 1024 * 1024)
                        .build()
                }.build()
    }

    @After fun teardown() {
        loader.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        directory.deleteRecursively()
    }

    @Test fun rowRequestsMqWithoutTryingLargerVariants() =
        runTest {
            serverRule.server.enqueue(imageResponse(jpegBytes(320, 180, Color.GREEN)))
            val rowRequest =
                request(320)
                    .newBuilder()
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .memoryCacheKey("art:$KEY:v7:t")
                    .build()
            val result = assertIs<SuccessResult>(loader.execute(rowRequest))
            assertEquals("art:$KEY:v7:t", result.memoryCacheKey?.key)
            assertEquals(DataSource.MEMORY_CACHE, assertIs<SuccessResult>(loader.execute(rowRequest)).dataSource)
            assertEquals(
                "/vi/abcdefghijk/mqdefault.jpg",
                serverRule.server
                    .takeRequest()
                    .url.encodedPath,
            )
            assertEquals(320, result.image.width)
            assertEquals(180, result.image.height)
            val offlineHero = request(1024).newBuilder().networkCachePolicy(CachePolicy.DISABLED).build()
            val cached = assertIs<SuccessResult>(loader.execute(offlineHero))
            assertEquals(DataSource.DISK, cached.dataSource)
            assertEquals(1, serverRule.server.requestCount)
        }

    @Test fun heroFallsThrough404AndCachesMissingAndSuccessfulVariantsOffline() =
        runTest {
            serverRule.server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.url.encodedPath.endsWith("/maxresdefault.jpg")) {
                            imageResponse(byteArrayOf(), code = 404)
                        } else {
                            imageResponse(jpegBytes(1280, 720, Color.GREEN))
                        }
                }
            assertIs<SuccessResult>(loader.execute(request(1024)))
            assertEquals(
                "/vi/abcdefghijk/maxresdefault.jpg",
                serverRule.server
                    .takeRequest()
                    .url.encodedPath,
            )
            assertEquals(
                "/vi/abcdefghijk/hq720.jpg",
                serverRule.server
                    .takeRequest()
                    .url.encodedPath,
            )
            val cached = assertIs<SuccessResult>(loader.execute(request(1024)))
            assertEquals(DataSource.DISK, cached.dataSource)
            assertEquals(2, serverRule.server.requestCount)
            val offlineRequest = request(1024).newBuilder().networkCachePolicy(CachePolicy.DISABLED).build()
            val offline = assertIs<SuccessResult>(loader.execute(offlineRequest))
            assertEquals(DataSource.DISK, offline.dataSource)
            assertEquals(2, serverRule.server.requestCount)
        }

    @Test fun everyFailureReachesMqThenLetterboxedHqAndCropsBeforeSquare() =
        runTest {
            serverRule.server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.url.encodedPath.endsWith("/hqdefault.jpg")) {
                            imageResponse(letterboxed(), "image/png")
                        } else {
                            imageResponse(byteArrayOf(), code = 503)
                        }
                }
            // Keep native pixels for the crop-order proof, without decoder resampling at the band edges.
            val squareRequest =
                request(1024)
                    .newBuilder()
                    .size(Size.ORIGINAL)
                    .transformations(SquareCrop())
                    .build()
            val result = assertIs<SuccessResult>(loader.execute(squareRequest))
            assertEquals(
                listOf("maxresdefault", "hq720", "mqdefault", "hqdefault"),
                (1..4).map {
                    serverRule.server
                        .takeRequest()
                        .url.encodedPath
                        .substringAfterLast('/')
                        .substringBefore('.')
                },
            )
            val bitmap = result.image.toBitmap()
            assertEquals(bitmap.width, bitmap.height)
            // Red letterbox bars would survive at the top/bottom if square cropping ran first.
            for (y in listOf(0, bitmap.height / 2, bitmap.height - 1)) {
                assertEquals(Color.GREEN, bitmap.getPixel(bitmap.width / 2, y))
            }
        }

    @Test fun uncachedOfflineReturnsErrorWithoutNetwork() =
        runTest {
            val offline = request(1024).newBuilder().networkCachePolicy(CachePolicy.DISABLED).build()
            assertIs<ErrorResult>(loader.execute(offline))
            assertEquals(0, serverRule.server.requestCount)
        }

    @Test fun pinnedRefBypassesRewriteAndPreservesMemoryIdentity() =
        runTest {
            val file = directory.resolve("pinned.jpg").apply { writeBytes(jpegBytes(320, 180, Color.BLUE)) }
            store.path = file.path.toPath()
            val original =
                request(1024)
                    .newBuilder()
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .memoryCacheKey("art:$KEY:v7:h")
                    .build()
            val result = assertIs<SuccessResult>(loader.execute(original))
            assertEquals(original.data, result.request.data)
            assertEquals("art:$KEY:v7:h", result.memoryCacheKey?.key)
            assertEquals(DataSource.DISK, result.dataSource)
            assertEquals(0, serverRule.server.requestCount)
            assertEquals(DataSource.MEMORY_CACHE, assertIs<SuccessResult>(loader.execute(original)).dataSource)
        }

    @Test fun plainUrlsAlsoRewriteAndNonThumbnailRequestsPassThrough() =
        runTest {
            serverRule.server.enqueue(imageResponse(jpegBytes(320, 180, Color.GREEN)))
            val plain = request(320).newBuilder().data(URL.replace("mqdefault", "hqdefault")).build()
            assertIs<SuccessResult>(loader.execute(plain))
            assertEquals(
                "/vi/abcdefghijk/mqdefault.jpg",
                serverRule.server
                    .takeRequest()
                    .url.encodedPath,
            )
            val file = directory.resolve("ordinary.jpg").apply { writeBytes(jpegBytes(144, 144, Color.BLUE)) }
            assertIs<SuccessResult>(loader.execute(request(320).newBuilder().data(file.path.toPath()).build()))
            assertEquals(1, serverRule.server.requestCount)
        }

    @Test fun commonCropHasExactCentralBandAndLeavesWideInputIntact() =
        runTest {
            assertEquals(ThumbnailCrop(0, 45, 480, 270), central169(480, 360))
            assertEquals(ThumbnailCrop(0, 0, 320, 180), central169(320, 180))
            val bitmap = Bitmap.createBitmap(480, 360, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.RED)
                drawRect(0f, 45f, 480f, 315f, Paint().apply { color = Color.GREEN })
            }
            val result = LetterboxCrop169().transform(bitmap, Size.ORIGINAL)
            assertEquals(480, result.width)
            assertEquals(270, result.height)
            assertEquals(Color.GREEN, result.getPixel(0, 0))
            assertEquals(Color.GREEN, result.getPixel(479, 269))
            assertEquals(Color.RED, bitmap.getPixel(0, 0))
        }

    private fun request(width: Int): ImageRequest =
        ImageRequest
            .Builder(app)
            .data(ArtworkRef(KEY, URL, 7))
            .size(width, width * 9 / 16)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()

    private fun letterboxed(): ByteArray {
        val bitmap = Bitmap.createBitmap(480, 360, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.RED)
            drawRect(0f, 45f, 480f, 315f, Paint().apply { color = Color.GREEN })
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private class SquareCrop : Transformation() {
        override val cacheKey = "test-square"

        override suspend fun transform(
            input: Bitmap,
            size: Size,
        ): Bitmap {
            val edge = minOf(input.width, input.height)
            return Bitmap.createBitmap(input, (input.width - edge) / 2, (input.height - edge) / 2, edge, edge)
        }
    }

    private class ThumbnailStore : ArtworkStore {
        var path: Path? = null

        override fun pinnedFile(key: String): Path? = path

        override fun pinnedPath(key: String): Path? = path

        override fun isPinned(key: String): Boolean = path != null

        override fun pin(
            ref: ArtworkRef,
            reason: PinReason,
            ownerId: Long,
        ) = Unit

        override fun unpin(
            key: String,
            reason: PinReason,
            ownerId: Long,
        ) = Unit

        override fun contentUri(
            key: String,
            version: Int,
        ): String = ""

        override suspend fun collectGarbage(): Int = 0
    }

    private companion object {
        val KEY = "u-" + "a".repeat(40)
        const val URL = "https://i.ytimg.com/vi/abcdefghijk/mqdefault.jpg"
    }
}
