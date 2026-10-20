// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.PortableYouTubePlayback
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.api.AudioPref
import ch.lkmc.neutrodyne.youtube.api.AudioQuality
import ch.lkmc.neutrodyne.youtube.api.BreakerState
import ch.lkmc.neutrodyne.youtube.api.ResolveResult
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.api.YouTubeResolveException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YouTubeMediaSourceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun failedCacheInitializationStillStreamsOnRetryThroughTheSameOwner() {
        Fixture(temporary.newFile()).use { f ->
            f.server.enqueue(MockResponse.Builder().body("abcdef").build())
            val source = f.factory.createDataSource()
            val spec = f.spec()
            assertThrows(java.io.IOException::class.java) { read(source, spec) }
            assertEquals("abc", read(source, spec))
            assertEquals(1, f.port.nativeCalls())
            assertEquals(1, f.server.requestCount)
            f.port.streams.invalidateAll()
            f.lmt++
            val change = assertThrows(YouTubeFormatChangedException::class.java) { f.h.resolve() }
            f.h.resolver.repin(1, change)
            f.server.enqueue(MockResponse.Builder().body("abcdef").build())
            assertEquals("abc", read(source, spec))
            assertEquals(2, f.port.nativeCalls())
            assertEquals(2, f.server.requestCount)
        }
    }

    @Test fun sharedVideoCacheRemainsPinnedUntilBothEpisodeOwnersDepart() {
        Fixture().use { f ->
            f.h.index.putAll(listOf(media(id = 2, type = SourceType.YOUTUBE_CHANNEL)))
            val first = f.h.resolve()
            val second = f.h.resolve(id = 2)
            assertEquals(first.key, second.key)
            f.h.owner
                .cache()
                .write(checkNotNull(first.key), "abc")
            f.h.resolver.unpin(1)
            runBlocking { f.h.owner.clearStreamingCache() }
            assertEquals(
                3L,
                f.h.owner
                    .cache()
                    .cacheSpace,
            )
            f.h.resolver.unpin(2)
            runBlocking { f.h.owner.clearStreamingCache() }
            assertEquals(
                0L,
                f.h.owner
                    .cache()
                    .cacheSpace,
            )
        }
    }

    @Test fun compatibleResolveUnpinPreservesOtherOwnersCache() =
        releaseAtPublication(Acquisition.COMPATIBLE, Departure.UNPIN)

    @Test fun compatibleResolveRepinPreservesOtherOwnersCache() =
        releaseAtPublication(Acquisition.COMPATIBLE, Departure.REPIN)

    @Test fun initialResolveUnpinCompensatesItsNewReference() =
        releaseAtPublication(
            Acquisition.INITIAL,
            Departure.UNPIN,
        )

    @Test fun initialResolveRepinCompensatesItsNewReference() =
        releaseAtPublication(
            Acquisition.INITIAL,
            Departure.REPIN,
        )

    private enum class Acquisition { INITIAL, COMPATIBLE }

    private enum class Departure { UNPIN, REPIN }

    private fun releaseAtPublication(
        acquisition: Acquisition,
        departure: Departure,
    ) {
        Fixture().use { f ->
            f.h.index.putAll(listOf(media(id = 2, type = SourceType.YOUTUBE_CHANNEL)))
            val second = f.h.resolve(id = 2)
            val cache = f.h.owner.cache()
            cache.write(checkNotNull(second.key), "abc")
            val entered = CountDownLatch(1)
            val response = CompletableDeferred<Unit>()
            val source: DataSource
            val spec: DataSpec
            when (acquisition) {
                Acquisition.COMPATIBLE -> {
                    spec = f.h.resolve(position = 3, length = 3)
                    // The existing lease bypasses the outer resolver's earlier pins lock.
                    source = f.cacheFactory.createDataSource()
                }

                Acquisition.INITIAL -> {
                    spec = f.spec(3, 3)
                    source = f.factory.createDataSource()
                    f.port.streams.invalidateAll()
                    f.port.reply {
                        entered.countDown()
                        response.await()
                        f.reply()
                    }
                }
            }
            val result = AtomicReference<Throwable>()
            val loader =
                Thread {
                    try {
                        read(source, spec)
                    } catch (error: Throwable) {
                        result.set(error)
                    }
                }
            val monitor = PlaybackTestStores.publicationMonitor(f.h.resolver)
            try {
                if (acquisition == Acquisition.INITIAL) {
                    loader.start()
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                }
                synchronized(monitor) {
                    if (acquisition == Acquisition.INITIAL) response.complete(Unit) else loader.start()
                    // This is the actual monitor inside resolve, after old was read and any
                    // initial reference acquired. JVM monitor entry cannot abort on job cancel.
                    awaitPublication(loader, monitor)
                    when (departure) {
                        Departure.UNPIN -> f.h.resolver.unpin(1)
                        Departure.REPIN -> f.h.resolver.repin(1)
                    }
                }
                loader.join(5000)
                assertFalse("Release must finish the actual loader", loader.isAlive)
                assertTrue("Expected interruption, got ${result.get()}", result.get() is InterruptedIOException)
                assertEquals(0, f.server.requestCount)
                assertEquals(if (acquisition == Acquisition.INITIAL) 2 else 1, f.port.nativeCalls())
                runBlocking { f.h.owner.clearStreamingCache() }
                assertEquals("The remaining episode still owns the cached bytes", 3L, cache.cacheSpace)
                f.h.resolver.unpin(2)
                runBlocking { f.h.owner.clearStreamingCache() }
                assertEquals("The last departure makes the bytes removable", 0L, cache.cacheSpace)
            } finally {
                response.complete(Unit)
                loader.interrupt()
                loader.join(5000)
            }
        }
    }

    private fun awaitPublication(
        loader: Thread,
        monitor: Any,
    ) {
        val threads = ManagementFactory.getThreadMXBean()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val info = threads.getThreadInfo(loader.id, 32) ?: break
            if (info.threadState == Thread.State.BLOCKED &&
                info.lockInfo?.identityHashCode == System.identityHashCode(monitor) &&
                info.lockInfo?.className == monitor.javaClass.name &&
                info.stackTrace.any { it.className == EpisodeResolver::class.java.name && it.methodName == "resolve" }
            ) {
                return
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }
        throw AssertionError("Loader did not block at resolve publication: ${threads.getThreadInfo(loader.id, 32)}")
    }

    @Test fun sameVideoUpstreamLoadBelongsToItsEpisodeRatherThanFirstMatchingCacheKey() {
        Fixture().use { f ->
            f.h.index.putAll(listOf(media(id = 2, type = SourceType.YOUTUBE_CHANNEL)))
            f.h.resolve()
            val second = f.h.resolve(id = 2)
            f.port.streams.invalidateAll()
            val entered = CountDownLatch(1)
            val result = AtomicReference<Throwable>()
            f.port.reply {
                entered.countDown()
                awaitCancellation()
            }
            val loader =
                Thread {
                    try {
                        read(f.cacheFactory.createDataSource(), second)
                    } catch (error: Throwable) {
                        result.set(error)
                    }
                }
            loader.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            try {
                f.h.resolver.unpin(1)
                loader.join(500)
                assertTrue("The other episode must retain its own waiter", loader.isAlive)
            } finally {
                f.h.resolver.unpin(2)
                loader.join(5000)
            }
            assertFalse(loader.isAlive)
            assertTrue(result.get() is InterruptedIOException)
            assertEquals(0, f.server.requestCount)
        }
    }

    @Test(timeout = 45_000)
    fun loaderWatchdogCancelsANonresponsiveResolverAtThirtyFiveSeconds() {
        val streams =
            ch.lkmc.neutrodyne.core.testing
                .FakeYouTubeStreamResolver()
        val caps =
            ch.lkmc.neutrodyne.core.testing
                .FakeYouTubeCapabilitiesSource()
        caps.capabilities.value =
            ch.lkmc.neutrodyne.youtube.api
                .YouTubeCapabilities(true, true, true, true, true, null)
        val cancelled =
            java.util.concurrent.atomic
                .AtomicBoolean()
        streams.resolve = { _, _, _ ->
            try {
                awaitCancellation()
            } finally {
                cancelled.set(true)
            }
        }
        SourceHarness(temporary.newFolder(), streams = streams, caps = caps).use { h ->
            h.index.putAll(listOf(media(type = SourceType.YOUTUBE_CHANNEL)))
            val started = System.nanoTime()
            val error = assertThrows(YouTubeResolveException::class.java) { h.resolve() }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertEquals(TransientKind.TIMEOUT, (error.result as ResolveResult.Transient).kind)
            assertTrue(elapsed >= 34_000 && elapsed < 42_000)
            assertTrue(cancelled.get())
        }
    }

    @Test fun interruptDuringPrerollStopsBeforeMediaOpen() {
        Fixture().use { f ->
            f.availableAt = f.clock.now() / 1000 + 30
            val result = AtomicReference<Throwable>()
            val loader =
                Thread {
                    try {
                        f.read(f.spec())
                    } catch (error: Throwable) {
                        result.set(error)
                    }
                }
            loader.start()
            runBlocking {
                kotlinx.coroutines.withTimeout(5000) {
                    while (f.port.nativeCalls() == 0) kotlinx.coroutines.delay(10)
                    // This cache lookup completes only after the real resolve has published its result.
                    f.port.streams.resolveAudio("abcdefghijk", AudioPref())
                }
            }
            loader.interrupt()
            loader.join(5000)
            assertFalse(loader.isAlive)
            assertTrue(result.get() is InterruptedIOException)
            assertEquals(0, f.server.requestCount)
        }
    }

    @Test fun actualChainRefreshes403AtSameBytePositionAndKeepsCanonicalKey() {
        Fixture().use { f ->
            f.server.enqueue(MockResponse.Builder().code(403).build())
            f.server.enqueue(
                MockResponse
                    .Builder()
                    .code(206)
                    .addHeader("Content-Range", "bytes 3-5/6")
                    .body("def")
                    .build(),
            )
            val spec = f.spec(3, 3)
            assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) { f.read(spec) }
            assertEquals(1, f.port.nativeCalls())
            assertEquals("def", f.read(spec))
            assertEquals(2, f.port.nativeCalls())
            assertEquals("bytes=3-5", f.server.takeRequest().headers["Range"])
            assertEquals("bytes=3-5", f.server.takeRequest().headers["Range"])
            assertEquals(
                setOf("yt:abcdefghijk:140"),
                f.h.owner
                    .cache()
                    .keys,
            )
            assertNull(
                ContentMetadata.getRedirectedUri(
                    f.h.owner
                        .cache()
                        .getContentMetadata("yt:abcdefghijk:140"),
                ),
            )
            assertEquals(null, f.server.takeRequest(1, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun cacheHitAvoidsHttpButEveryHoleUsesTheSameRealResolver() {
        Fixture().use { f ->
            f.server.enqueue(
                MockResponse
                    .Builder()
                    .code(206)
                    .addHeader("Content-Range", "bytes 0-2/6")
                    .body("abc")
                    .build(),
            )
            f.server.enqueue(
                MockResponse
                    .Builder()
                    .code(206)
                    .addHeader("Content-Range", "bytes 3-5/6")
                    .body("def")
                    .build(),
            )
            assertEquals("abc", f.read(f.spec(0, 3)))
            assertEquals("abc", f.read(f.spec(0, 3)))
            assertEquals(1, f.server.requestCount)
            val cachedSpec = f.h.resolve(position = 3, length = 3)
            f.clock.nowMs += 5 * 60 * 60_000L
            f.clock.elapsedMs += 5 * 60 * 60_000L
            // Direct cached-layer reopen deliberately bypasses outer ResolvingDataSource.
            assertEquals("def", read(f.cacheFactory.createDataSource(), cachedSpec))
            assertEquals(2, f.port.nativeCalls())
            assertEquals(2, f.server.requestCount)
        }
    }

    @Test fun fullRepresentationRejectsSameItagDrcTrackLengthAndLmtBeforeHttp() {
        for (change in listOf("drc", "track", "clen", "lmt")) {
            Fixture().use { f ->
                val original = f.h.resolve()
                f.port.streams.invalidate("abcdefghijk")
                f.format = if (change == "drc") "140-drc" else "140"
                f.tags = if (change == "track") "lang=en:acont=original" else null
                f.length = if (change == "clen") 9 else 6
                f.lmt = if (change == "lmt") 124 else 123
                val exception = assertThrows(YouTubeFormatChangedException::class.java) { f.h.resolve() }
                assertEquals("140", exception.old.formatId)
                assertEquals(0, f.server.requestCount)
                assertEquals(2, f.port.nativeCalls())
                assertEquals("yt:abcdefghijk:140", original.key)
            }
        }
    }

    @Test fun crossSessionLengthAndRedirectMetadataAreClearedBeforeCacheRead() {
        Fixture().use { f ->
            val key = checkNotNull(f.h.resolve().key)
            val cache = f.h.owner.cache()
            cache.write(key, "stale!")
            cache.applyContentMetadataMutations(
                key,
                ContentMetadataMutations().also {
                    ContentMetadataMutations.setContentLength(it, 99)
                    ContentMetadataMutations.setRedirectedUri(it, Uri.parse("https://old.example/signed"))
                },
            )
            f.h.resolver.unpin(1)
            f.h.resolve()
            assertEquals(0, cache.cacheSpace)
            assertEquals(C.LENGTH_UNSET.toLong(), ContentMetadata.getContentLength(cache.getContentMetadata(key)))
            assertNull(ContentMetadata.getRedirectedUri(cache.getContentMetadata(key)))
            cache.write(key, "abc")
            f.h.resolver.unpin(1)
            f.h.resolve()
            assertEquals(3, cache.cacheSpace)
        }
    }

    @Test fun invalidationBudgetSurvivesRepinAndExpiresAtSixtySeconds() {
        Fixture().use { f ->
            repeat(4) { f.server.enqueue(MockResponse.Builder().code(410).build()) }
            repeat(3) { attempt ->
                assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) { f.read(f.spec()) }
                if (attempt == 0) f.h.resolver.repin(1)
            }
            assertEquals(3, f.port.nativeCalls())
            f.clock.elapsedMs += 60_000
            assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) { f.read(f.spec()) }
            f.h.resolve()
            assertEquals(4, f.port.nativeCalls())
            f.h.resolver.unpin(1)
            f.server.enqueue(MockResponse.Builder().code(410).build())
            assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) { f.read(f.spec()) }
            f.h.resolve()
            assertEquals(5, f.port.nativeCalls())
        }
    }

    @Test fun media429PausesSharedHealthWithoutTakingExtractionReservation() {
        Fixture().use { f ->
            f.server.enqueue(MockResponse.Builder().code(429).build())
            val error = assertThrows(YouTubeResolveException::class.java) { f.read(f.spec()) }
            assertEquals(TransientKind.RATE_LIMITED, (error.result as ResolveResult.Transient).kind)
            val until = f.port.health.state.value.rateLimitedUntil
            assertEquals(f.clock.now() + 30 * 60_000L, until)
            assertEquals(BreakerState.CLOSED, f.port.health.state.value.breaker)
            assertEquals(1, f.port.nativeCalls())
        }
    }

    @Test fun unpinAndThreadInterruptionCancelRealWaiterWithoutLateOpen() {
        for (interrupt in listOf(false, true)) {
            Fixture().use { f ->
                val entered = CountDownLatch(1)
                val result = AtomicReference<Throwable>()
                f.port.reply {
                    entered.countDown()
                    awaitCancellation()
                }
                val loader =
                    Thread {
                        try {
                            f.read(f.spec())
                        } catch (error: Throwable) {
                            result.set(error)
                        }
                    }
                loader.start()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                if (interrupt) loader.interrupt() else f.h.resolver.unpin(1)
                loader.join(5000)
                assertFalse(loader.isAlive)
                assertTrue(result.get() is InterruptedIOException)
                runBlocking {
                    kotlinx.coroutines.withTimeout(5000) {
                        while (f.port.cancellations() != 1) kotlinx.coroutines.delay(10)
                    }
                }
                assertEquals(1, f.port.cancellations())
                assertEquals(0, f.server.requestCount)
                assertEquals(
                    0,
                    f.h.owner
                        .cache()
                        .cacheSpace,
                )
            }
        }
    }

    @Test fun preferenceSnapshotAndPrerollUseTheExistingPorts() {
        Fixture().use { f ->
            f.h.pref = AudioPref(AudioQuality.DATA_SAVER, true, preferredLanguage = "de")
            f.h.resolve()
            f.h.pref = AudioPref(AudioQuality.OPUS)
            f.h.resolver.repin(1)
            // Selection's original preference remains part of the flight/cache key after pinning.
            f.h.resolve()
            assertEquals(1, f.port.nativeCalls())
            f.h.resolver.unpin(1)
            f.availableAt = f.clock.now() / 1000 + 31
            f.port.streams.invalidateAll()
            val error = assertThrows(YouTubeResolveException::class.java) { f.h.resolve() }
            assertEquals(TransientKind.NETWORK, (error.result as ResolveResult.Transient).kind)
            assertEquals(0, f.server.requestCount)
        }
    }

    private inner class Fixture(
        directory: java.io.File = temporary.newFolder(),
    ) : AutoCloseable {
        val clock = TestClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port =
            PortableYouTubePlayback(clock, scope) { invalidate ->
                PlaybackTestStores.health(FakeSettingsRepository(), clock, scope, invalidate)
            }
        val h =
            SourceHarness(
                directory,
                streams = port.streams,
                caps = port.capabilities,
                health = port.health,
                clock = clock,
            )
        val server = MockWebServer()
        var format = "140"
        var tags: String? = null
        var length = 6
        var lmt = 123
        var availableAt: Long? = null

        init {
            runBlocking { port.health.awaitLoaded() }
            server.start()
            h.index.putAll(listOf(media(type = SourceType.YOUTUBE_CHANNEL)))
            port.reply { reply() }
        }

        private val clients = mediaClients()
        val cacheFactory: DataSource.Factory get() =
            CacheDataSource
                .Factory()
                .setCache(h.owner.cache())
                .setUpstreamDataSourceFactory(GuardedHttpDataSource.Factory(clients, h.resolver))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val factory: DataSource.Factory get() =
            ResolvingDataSource.Factory(
                DefaultDataSource.Factory(RuntimeEnvironment.getApplication(), cacheFactory),
                h.resolver,
            )

        fun spec(
            position: Long = 0,
            length: Long = 3,
        ) = DataSpec
            .Builder()
            .setUri(episodeUri(1))
            .setPosition(position)
            .setLength(length)
            .build()

        fun read(spec: DataSpec) = read(factory.createDataSource(), spec)

        fun reply(): String {
            val url = server.url("/audio?itag=140&clen=$length&lmt=$lmt&expire=4102444800&sig=private")
            val xtags = tags?.let { "\"$it\"" } ?: "null"
            val reply = """{"videoId":"abcdefghijk","duration":120,"liveStatus":"not_live","availability":null,
                "releaseTs":null,"ageLimit":0,"formats":[{"formatId":"$format","url":"$url","ext":"m4a",
                "acodec":"mp4a.40.2","abr":128.0,"tbr":null,"filesize":$length,"language":null,
                "languagePreference":-1,"formatNote":"medium","protocol":"http","availableAt":$availableAt,"xtags":$xtags}]}"""
            if (tags == null) return reply
            val first = reply.substringAfter("\"formats\":[").removeSuffix("]}")
            val second = first.replace(xtags, "\"lang=de:acont=dubbed\"")
            return reply.substringBefore("\"formats\":[") + "\"formats\":[" + first + "," + second + "]}"
        }

        override fun close() {
            h.close()
            scope.cancel()
            server.close()
        }
    }
}

private fun read(
    source: DataSource,
    spec: DataSpec,
): String =
    try {
        source.open(spec)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(32)
        while (true) {
            val n = source.read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) break
            output.write(buffer, 0, n)
        }
        output.toString("UTF-8")
    } finally {
        source.close()
    }
