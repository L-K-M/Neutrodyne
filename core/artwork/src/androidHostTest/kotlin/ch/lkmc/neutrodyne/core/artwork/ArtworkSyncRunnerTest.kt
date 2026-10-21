// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import android.app.Application
import android.graphics.BitmapFactory
import android.graphics.Color
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.common.art.Monogram
import ch.lkmc.neutrodyne.core.database.ArtworkEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.model.art.MonogramMode
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * `ArtworkSyncRunner` end to end on Android (08 ArtworkSyncWorker): the real
 * [DefaultArtworkStore], a real `TestDb` Room database, the real [AndroidArtworkCodec] and
 * [AndroidMonogramRenderer], and the real Coil `ImageLoader` on the OkHttp fetcher against
 * `MockWebServer3`. All image bytes are generated at runtime with `Bitmap`/`Canvas` :
 * `GraphicsMode.NATIVE` so decode/encode touch real pixels. [TestClock] drives the backoff and
 * monthly-refresh windows; the scheduler's long-lived collector runs on `backgroundScope`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkSyncRunnerTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server
    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var client: OkHttpClient
    private lateinit var loader: ImageLoader

    @Before
    fun setUp() {
        client = artworkTestImageClient()
        loader =
            ImageLoader
                .Builder(app)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
                .build()
    }

    @After
    fun tearDown() {
        loader.shutdown()
    }

    /** Everything the runner touches, wired exactly like the app graph binds it. */
    private inner class Harness(
        scope: CoroutineScope,
        io: CoroutineDispatcher,
    ) : AutoCloseable {
        val clock = TestClock()
        val db: NeutrodyneDatabase = TestDb.inMemory(app, clock = clock)
        val work = RecordingWorkScheduler()
        val scheduler = ArtworkSyncScheduler(work, scope)
        val store = DefaultArtworkStore(db, StoragePaths(app), AndroidArtworkContentUris(app), scope, scheduler, io)
        val runner =
            ArtworkSyncRunner(
                db,
                store,
                AndroidArtworkCodec(),
                AndroidMonogramRenderer(),
                Provider { loader },
                app,
                scheduler,
                clock,
                io,
            )

        suspend fun podcast(
            key: String,
            url: String?,
            title: String = "Synthetic Show",
            latestEpisodeAt: Long? = null,
        ): Long = db.seedArtworkPodcast(key, url, title, latestEpisodeAt)

        suspend fun byKey(key: String): ArtworkEntity = assertNotNull(db.artworkDao().byKey(key))

        fun file(name: String): File = File(artworkDir(app), name)

        override fun close() {
            runCatching { db.close() }
            artworkDir(app).deleteRecursively()
        }
    }

    private class RecordingWorkScheduler : ArtworkWorkScheduler {
        val enqueues = AtomicInteger()

        override suspend fun enqueue() {
            enqueues.incrementAndGet()
        }
    }

    private suspend fun TestScope.harnessed(block: suspend Harness.() -> Unit) {
        val harness = Harness(backgroundScope, Dispatchers.IO)
        try {
            harness.block()
        } finally {
            harness.close()
        }
    }

    @Test
    fun downscalesA3000PxJpegIntoThePinnedFile() =
        runTest {
            harnessed {
                val url = server.url("/big.jpg").toString()
                val key = uKey(url)
                podcast(key, url)
                server.enqueue(imageResponse(jpegBytes(3000, 1500, NAVY)))

                runner.runOnce()

                val row = byKey(key)
                assertEquals(url, row.url)
                assertEquals("$key.jpg", row.localPath)
                assertEquals(1, row.version)
                assertNull(row.lastError)
                assertTrue(row.width!! <= 1024 && row.height!! <= 1024)
                assertTrue(abs(row.width!! - 2.0 * row.height!!) <= 4, "aspect kept: ${row.width}x${row.height}")
                val decoded = assertNotNull(BitmapFactory.decodeFile(file(row.localPath!!).path))
                assertEquals(row.width, decoded.width)
                assertEquals(row.height, decoded.height)
                assertEquals(1, server.requestCount)
            }
        }

    @Test
    fun alphaPngKeepsItsExtensionAndTransparency() =
        runTest {
            harnessed {
                val url = server.url("/logo.png").toString()
                val key = uKey(url)
                podcast(key, url)
                server.enqueue(imageResponse(alphaPngBytes(400, 300, AMBER), "image/png"))

                runner.runOnce()

                val row = byKey(key)
                assertEquals("$key.png", row.localPath)
                assertEquals(400, row.width)
                assertEquals(300, row.height)
                val decoded = assertNotNull(BitmapFactory.decodeFile(file(row.localPath!!).path))
                assertEquals(0, Color.alpha(decoded.getPixel(399, 299)))
                assertEquals(0xFF, Color.alpha(decoded.getPixel(10, 10)))
            }
        }

    @Test
    fun a100PxImageIsTooSmallUnpinnedAndBackedOff() =
        runTest {
            harnessed {
                val url = server.url("/tiny.jpg").toString()
                val key = uKey(url)
                podcast(key, url)
                server.enqueue(imageResponse(jpegBytes(100, 100, NAVY)))

                runner.runOnce()

                val row = byKey(key)
                assertEquals("TOO_SMALL:1", row.lastError)
                assertEquals(clock.now(), row.fetchedAt)
                assertNull(row.localPath)
                assertNull(store.pinnedFile(key))
                assertFalse(store.isPinned(key))

                runner.runOnce() // attempt-1 backoff of 1h still active: no second request
                assertEquals(1, server.requestCount)

                clock.advanceBy(61.minutes)
                server.enqueue(imageResponse(jpegBytes(600, 600, NAVY)))
                runner.runOnce()

                val retried = byKey(key)
                assertNull(retried.lastError)
                assertEquals("$key.jpg", retried.localPath)
                assertEquals(2, server.requestCount)
            }
        }

    @Test
    fun a144PxLogoIsAccepted() =
        runTest {
            harnessed {
                val url = server.url("/logo144.jpg").toString()
                val key = uKey(url)
                podcast(key, url)
                server.enqueue(imageResponse(jpegBytes(144, 144, NAVY)))

                runner.runOnce()

                val row = byKey(key)
                assertNull(row.lastError)
                assertEquals("$key.jpg", row.localPath)
                assertEquals(144, row.width)
                assertEquals(144, row.height)
                assertEquals(1, row.version)
                assertNotNull(store.pinnedFile(key))
            }
        }

    @Test
    fun http404RecordsAttemptsBacksOff1h6h24h7dAndKeepsTheFile() =
        runTest {
            harnessed {
                val url = server.url("/gone.jpg").toString()
                val key = uKey(url)
                podcast(key, url)
                server.enqueue(imageResponse(jpegBytes(640, 320, NAVY)))
                runner.runOnce()
                assertEquals("$key.jpg", byKey(key).localPath)
                val file = file("$key.jpg")
                assertTrue(file.exists())

                repeat(5) { server.enqueue(notFoundResponse()) }

                clock.advanceBy(31.days) // the monthly refresh makes the retry eligible
                runner.runOnce()
                assertEquals("HTTP_404:1", byKey(key).lastError)
                assertEquals(2, server.requestCount)
                assertTrue(file.exists())

                clock.advanceBy(30.minutes)
                runner.runOnce()
                assertEquals(2, server.requestCount) // 1h backoff still active

                clock.advanceBy(31.minutes)
                runner.runOnce()
                assertEquals("HTTP_404:2", byKey(key).lastError)
                assertEquals(3, server.requestCount)

                clock.advanceBy(5.hours)
                runner.runOnce()
                assertEquals(3, server.requestCount) // 6h backoff still active

                clock.advanceBy(61.minutes)
                runner.runOnce()
                assertEquals("HTTP_404:3", byKey(key).lastError)
                assertEquals(4, server.requestCount)

                clock.advanceBy(23.hours)
                runner.runOnce()
                assertEquals(4, server.requestCount) // 24h backoff still active

                clock.advanceBy(61.minutes)
                runner.runOnce()
                assertEquals("HTTP_404:4", byKey(key).lastError)
                assertEquals(5, server.requestCount)

                clock.advanceBy(6.days)
                runner.runOnce()
                assertEquals(5, server.requestCount) // 7d backoff still active

                clock.advanceBy(25.hours)
                runner.runOnce()
                assertEquals("HTTP_404:5", byKey(key).lastError)
                assertEquals(6, server.requestCount)

                // The failed fetches never touched the stored file, its path or its version.
                val row = byKey(key)
                assertEquals("$key.jpg", row.localPath)
                assertEquals(url, row.url)
                assertEquals(1, row.version)
                assertTrue(file.exists())
            }
        }

    @Test
    fun monthlyRefreshWithIdenticalBytesKeepsTheVersion() =
        runTest {
            harnessed {
                val url = server.url("/same.jpg").toString()
                val key = uKey(url)
                podcast(key, url)
                val bytes = jpegBytes(400, 300, NAVY)
                server.enqueue(imageResponse(bytes))
                runner.runOnce()
                val stored = byKey(key)
                assertEquals(1, stored.version)

                clock.advanceBy(31.days)
                server.enqueue(imageResponse(bytes))
                runner.runOnce()

                val refreshed = byKey(key)
                assertEquals(1, refreshed.version)
                assertEquals(stored.localPath, refreshed.localPath)
                assertTrue(refreshed.fetchedAt!! > stored.fetchedAt!!)
                // A monthly refresh bypasses the disk cache (WRITE_ONLY): the server is hit.
                assertEquals(2, server.requestCount)
            }
        }

    @Test
    fun changedBytesBumpVersionAndRemoveTheOldExtension() =
        runTest {
            harnessed {
                val url = server.url("/swapped.png").toString()
                val key = uKey(url)
                podcast(key, url)
                server.enqueue(imageResponse(alphaPngBytes(400, 300, AMBER), "image/png"))
                runner.runOnce()
                assertEquals("$key.png", byKey(key).localPath)
                assertTrue(file("$key.png").exists())

                clock.advanceBy(31.days)
                server.enqueue(imageResponse(jpegBytes(400, 300, NAVY)))
                runner.runOnce()

                val row = byKey(key)
                assertEquals("$key.jpg", row.localPath)
                assertEquals(2, row.version)
                assertTrue(file("$key.jpg").exists())
                assertFalse(file("$key.png").exists())
                assertEquals(2, server.requestCount)
            }
        }

    @Test
    fun aMissingPinnedFileIsRefetched() =
        runTest {
            harnessed {
                val url = server.url("/lost.jpg").toString()
                val key = uKey(url)
                podcast(key, url)
                val bytes = jpegBytes(400, 300, NAVY)
                server.enqueue(imageResponse(bytes))
                runner.runOnce()
                val original = file("$key.jpg").readBytes()
                check(file("$key.jpg").delete())

                server.enqueue(imageResponse(bytes))
                runner.runOnce()

                val row = byKey(key)
                assertNull(row.lastError)
                assertTrue(file("$key.jpg").exists())
                // The bytes are identical but the file had to be rewritten, so the version moves.
                assertEquals(2, row.version)
                assertTrue(file("$key.jpg").readBytes().contentEquals(original))
                assertEquals(2, server.requestCount)
            }
        }

    @Test
    fun monogramRowsRerenderWhenTheTitleDescriptorChanges() =
        runTest {
            harnessed {
                val key = "m-" + "e".repeat(40)
                val podcastId = podcast(key, url = null, title = "Alpha Show")

                runner.runOnce()

                val firstSpec = Monogram.spec("Alpha Show")
                val tone = firstSpec.colors(MonogramMode.RASTER).first
                val first = byKey(key)
                assertEquals(monogramDescriptor(firstSpec), first.url)
                assertEquals("$key.jpg", first.localPath)
                assertEquals(512, first.width)
                assertEquals(512, first.height)
                assertEquals(tone, first.seedArgb)
                assertEquals(tone, first.avgArgb)
                assertEquals(1, first.version)
                assertNull(first.lastError)
                assertEquals(0, server.requestCount) // rendered locally, never via HTTP

                db.podcastDao().setCustomTitle(podcastId, "Beta Show")
                runner.runOnce()

                val second = byKey(key)
                assertEquals(monogramDescriptor(Monogram.spec("Beta Show")), second.url)
                assertTrue(second.url != first.url)
                assertEquals(2, second.version)
                assertEquals(0, server.requestCount)
            }
        }

    @Test
    fun produceIsBoundedToFourConcurrentFetchesAcrossEightRowBatches() =
        runTest {
            harnessed {
                val inflight = AtomicInteger()
                val maxSeen = AtomicInteger()
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val now = inflight.incrementAndGet()
                            maxSeen.accumulateAndGet(now, Integer::max)
                            try {
                                Thread.sleep(150)
                            } finally {
                                inflight.decrementAndGet()
                            }
                            return imageResponse(jpegBytes(320, 200, NAVY))
                        }
                    }
                repeat(12) { i ->
                    val url = server.url("/cover/$i.jpg").toString()
                    podcast(uKey(url), url)
                }

                runner.runOnce()

                assertEquals(12, server.requestCount)
                assertTrue(maxSeen.get() in 2..4, "expected bounded parallelism, saw ${maxSeen.get()}")
                assertEquals(12, db.artworkDao().pinnedRows().size)
            }
        }

    @Test
    fun aZeroDeadlineEnqueuesAContinuationWithoutFetching() =
        runTest {
            harnessed {
                val url = server.url("/later.jpg").toString()
                podcast(uKey(url), url)

                runner.runOnce(Duration.ZERO)

                assertEquals(1, work.enqueues.get())
                assertEquals(0, server.requestCount)
                assertNull(db.artworkDao().byKey(uKey(url)))
            }
        }

    @Test
    fun aDeadlineBetweenBatchesAppliesTheFinishedBatchAndEnqueuesNow() =
        runTest {
            harnessed {
                repeat(9) { i ->
                    val url = server.url("/c$i.jpg").toString()
                    podcast(uKey(url), url)
                }
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            clock.advanceBy(10.minutes) // burn the 5-minute budget inside batch one
                            return imageResponse(jpegBytes(320, 200, NAVY))
                        }
                    }

                runner.runOnce(5.minutes)

                assertEquals(8, server.requestCount)
                assertEquals(8, db.artworkDao().pinnedRows().size)
                assertEquals(1, work.enqueues.get())
            }
        }

    @Test
    fun garbageCollectsUnreferencedRowsAndRecountsPins() =
        runTest {
            harnessed {
                val keyA = uKey(server.url("/a.jpg").toString())
                val keyB = uKey(server.url("/b.jpg").toString())
                val keyC = uKey(server.url("/c.jpg").toString())
                val keyD = uKey(server.url("/d.jpg").toString())
                podcast(keyA, server.url("/a.jpg").toString())
                val podcastB = podcast(keyB, server.url("/b.jpg").toString())
                val podcastC = podcast(keyC, server.url("/c.jpg").toString())
                db.seedDownloadedArtwork(podcastC, keyD, server.url("/d.jpg").toString())
                val orphan = "u-" + "f".repeat(40)
                db
                    .artworkDao()
                    .upsert(
                        ArtworkEntity(
                            key = orphan,
                            url = "https://synthetic.test/orphan.jpg",
                            localPath = "$orphan.jpg",
                        ),
                    )
                file("$orphan.jpg").apply {
                    parentFile!!.mkdirs()
                    writeBytes(jpegBytes(64, 64, NAVY))
                }
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            imageResponse(jpegBytes(320, 200, NAVY))
                    }

                runner.runOnce()

                // The orphan was never referenced, so GC dropped its row and file in the same run.
                assertNull(db.artworkDao().byKey(orphan))
                assertFalse(file("$orphan.jpg").exists())
                assertEquals(
                    setOf(keyA, keyB, keyC, keyD),
                    db
                        .artworkDao()
                        .pinnedRows()
                        .map { it.key }
                        .toSet(),
                )
                assertEquals(1, byKey(keyA).pinCount)
                assertEquals(1, byKey(keyD).pinCount)

                db.podcastDao().deleteCascade(podcastB, clock.now())
                runner.runOnce()

                assertNull(db.artworkDao().byKey(keyB))
                assertFalse(file("$keyB.jpg").exists())
                assertEquals(
                    setOf(keyA, keyC, keyD),
                    db
                        .artworkDao()
                        .pinnedRows()
                        .map { it.key }
                        .toSet(),
                )
                assertEquals(4, server.requestCount) // nothing surviving was refetched
            }
        }

    @Test
    fun cancellingTheRunPropagatesOutOfProduce() =
        runTest {
            harnessed {
                val url = server.url("/slow.jpg").toString()
                podcast(uKey(url), url)
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .addHeader("Content-Type", "image/jpeg")
                        .headersDelay(5, TimeUnit.SECONDS)
                        .body(okio.Buffer().write(jpegBytes(320, 200, NAVY)))
                        .build(),
                )

                var thrown: Throwable? = null
                val job =
                    launch(Dispatchers.IO) {
                        try {
                            runner.runOnce()
                        } catch (t: Throwable) {
                            thrown = t
                        }
                    }
                assertNotNull(server.takeRequest(10, TimeUnit.SECONDS)) // request in flight
                job.cancelAndJoin()

                assertIs<CancellationException>(thrown)
            }
        }

    private companion object {
        // ARGB hex overflows a signed Int literal; the runtime cast is intentional.
        val NAVY = 0xFF194B7C.toInt()
        val AMBER = 0xFFC5821B.toInt()
    }
}
