// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import ch.lkmc.neutrodyne.core.testing.FakeDesktopSourceResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okio.Buffer
import org.junit.Rule
import java.lang.foreign.MemorySegment
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Production engine proofs. Every output explicitly selects miniaudio's null backend. */
class FfAudioEngineTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val item = EngineItem(1, "ep:1:pin", true)

    @Test
    fun livePositionAndPauseFollowConsumedFramesAtEverySpeed() {
        for (speed in listOf(0.5f, 1f, 2f, 3f)) {
            engine().use { engine ->
                engine.setSpeed(speed)
                engine.setWindow(listOf(item), 0, null)
                engine.play()
                awaitReady(engine)
                repeat(3) {
                    await("enough consumed frames") { (engine.outputFramesPlayed?.invoke() ?: 0L) > 2_000L }
                    engine.pause()
                    val played = requireNotNull(engine.outputFramesPlayed).invoke()
                    val period = period(engine)
                    val reference = ((played - period).coerceAtLeast(0) * speed * 1_000 / RATE).toLong()
                    assertTrue(
                        abs(engine.positionMs() - reference) <= 50,
                        "${speed}x: ${engine.positionMs()} vs $reference",
                    )
                    val frozen = engine.positionMs()
                    Thread.sleep(30)
                    assertEquals(frozen, engine.positionMs())
                    engine.play()
                }
                await("end of item") { engine.state.value == EngineState.Ended }
                assertTrue(abs(engine.positionMs() - 2_000) <= 50)
            }
        }
    }

    // The container duration stays unset because preparation skips avformat_find_stream_info, so
    // the demuxer must fall back to the audio stream's duration (MD1a.2 stores it as measured).
    @Test
    fun tracksKnownReportsTheMediaDuration() {
        engine().use { engine ->
            val known = CompletableFuture<EngineEvent.TracksKnown>()
            val scope = CoroutineScope(Dispatchers.Default)
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                engine.events.filterIsInstance<EngineEvent.TracksKnown>().collect { known.complete(it) }
            }
            try {
                engine.setWindow(listOf(item), 0, null)
                val durationMs = known.get(10, TimeUnit.SECONDS).durationMs
                assertTrue(durationMs != null && abs(durationMs - 2_000) <= 20, "duration: $durationMs")
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun deviceRateConversionKeepsTheSeekTimeline() {
        val deviceRate = 48_000
        for (speed in listOf(0.5f, 3f)) {
            engine(rate = deviceRate).use { engine ->
                engine.setSpeed(speed)
                engine.setWindow(listOf(item), 0, 700)
                engine.play()
                await("resampled playback") { engine.positionMs() >= 1_000 }
                engine.pause()
                val played = requireNotNull(engine.outputFramesPlayed).invoke()
                val reference = 700 + ((played - period(engine)).coerceAtLeast(0) * speed * 1_000 / deviceRate).toLong()
                assertTrue(abs(engine.positionMs() - reference) <= 50)
            }
        }
    }

    @Test
    fun seekKeepsItsAnchorThroughBufferingAndRecreatesThePipeline() {
        engine().use { engine ->
            engine.setWindow(listOf(item), 0, 700)
            assertEquals(700L, engine.positionMs())
            awaitReady(engine)
            assertEquals(700L, engine.positionMs())
            engine.seekTo(1_100)
            assertEquals(1_100, engine.positionMs())
            awaitReady(engine)
            assertTrue(abs(engine.positionMs() - 1_100) <= 1)
            engine.play()
            await("seek playback") { engine.positionMs() >= 1_200 }
            engine.pause()
            assertTrue(engine.positionMs() in 1_200..1_400)
        }
    }

    @Test
    fun tailDiffDoesNotResolveOrRestartTheCurrentItem() {
        val resolver = resolver()
        engine(resolver).use { engine ->
            engine.setWindow(listOf(item, EngineItem(2, "ep:2:pin", true)), 0, 600)
            awaitReady(engine)
            engine.updateWindow(
                EngineWindowDiff(
                    removed = setOf(2),
                    inserted = listOf(PlacedItem(1, EngineItem(3, "ep:3:pin", true))),
                ),
            )
            assertEquals(600L, engine.positionMs())
            engine.play()
            await("playback after tail edit") { engine.positionMs() >= 650 }
            synchronized(resolver) { assertEquals(listOf(item to 0), resolver.calls) }
        }
    }

    @Test
    fun speedAndSkipChangesApplyDuringPlayback() {
        engine().use { engine ->
            engine.setWindow(listOf(item), 0, null)
            engine.play()
            await("initial playback") { engine.positionMs() > 100 }
            engine.setSpeed(2f)
            engine.setSkipSilence(true)
            await("drain queued old-speed audio") { engine.positionMs() > 800 }
            engine.pause()
            val beforePosition = engine.positionMs()
            val beforeFrames = requireNotNull(engine.outputFramesPlayed).invoke()
            engine.play()
            await("new-speed output") { requireNotNull(engine.outputFramesPlayed).invoke() > beforeFrames + 4_000 }
            engine.pause()
            val frameDelta = requireNotNull(engine.outputFramesPlayed).invoke() - beforeFrames
            val referenceDelta = frameDelta * 2_000 / RATE
            assertTrue(abs(engine.positionMs() - beforePosition - referenceDelta) <= 50)
        }
    }

    @Test
    fun silenceGapKeepsTheLiveReferenceTimeline() {
        val file = writeTestWave(parityInput("speech"))
        try {
            for (speed in listOf(0.5f, 1f, 2f, 3f)) {
                engine(resolver(file)).use { engine ->
                    engine.setSpeed(speed)
                    engine.setSkipSilence(true)
                    engine.setWindow(listOf(item), 0, null)
                    engine.play()
                    awaitReady(engine)
                    var beforeGap = false
                    var afterGap = false
                    var samples = 0
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (engine.state.value != EngineState.Ended && System.nanoTime() < deadline) {
                        assertHealthy(engine)
                        val reader = requireNotNull(engine.outputFramesPlayed)
                        val before = reader()
                        val actual = engine.positionMs()
                        val after = reader()
                        val period = period(engine)
                        if (before == after && before > period && engine.state.value != EngineState.Ended) {
                            val retained = ((before - period) * speed.toDouble()).toLong()
                            val sourceFrame = retained + if (retained >= 18_614) 6_382 else 0
                            assertTrue(
                                abs(actual - sourceFrame * 1_000 / RATE) <= 50,
                                "${speed}x: live silence position $actual",
                            )
                            if (retained < 18_614) beforeGap = true else afterGap = true
                            samples++
                        }
                        Thread.sleep(1)
                    }
                    assertEquals(EngineState.Ended, engine.state.value)
                    assertTrue(samples >= 5 && beforeGap && afterGap)
                    assertTrue(abs(engine.positionMs() - 1_000) <= 50)
                }
            }
        } finally {
            Files.delete(file)
        }
    }

    @Test
    fun pauseAndReplacementAbortAnOpeningSource() {
        val blocked = BlockedSource(Files.readAllBytes(media()))
        val first = AtomicBoolean(true)
        engine(open = { if (first.getAndSet(false)) blocked else FileByteSource(media()) }).use { engine ->
            engine.setWindow(listOf(item), 0, null)
            engine.play()
            assertTrue(blocked.entered.await(5, TimeUnit.SECONDS))
            engine.pause()
            engine.seekTo(900)
            assertTrue(blocked.aborted.get())
            awaitReady(engine)
            val reader = requireNotNull(engine.outputFramesPlayed)
            Thread.sleep(30)
            assertEquals(0L, reader())
            assertEquals(900L, engine.positionMs())
            assertFalse((engine.state.value as EngineState.Ready).playing)
        }
    }

    @Test
    fun closeAbortsProductionPreparation() {
        val blocked = BlockedSource(Files.readAllBytes(media()))
        val engine = engine(open = { blocked })
        engine.setWindow(listOf(item), 0, null)
        assertTrue(blocked.entered.await(5, TimeUnit.SECONDS))
        val finished = CountDownLatch(1)
        val closer =
            Thread {
                try {
                    engine.close()
                } finally {
                    finished.countDown()
                }
            }
        closer.start()
        try {
            assertTrue(finished.await(1_500, TimeUnit.MILLISECONDS), "close did not abort native preparation")
            assertTrue(blocked.aborted.get())
        } finally {
            blocked.abort()
            closer.join(6_000)
        }
        assertFalse(closer.isAlive)
    }

    @Test
    fun forbiddenOpenIsReresolvedOnceThroughAvio() {
        val directory = Files.createTempDirectory("engine-http")
        try {
            val server = serverRule.server
            server.enqueue(MockResponse.Builder().code(403).build())
            server.enqueue(MockResponse.Builder().body(Buffer().write(Files.readAllBytes(media()))).build())
            val resolver =
                FakeDesktopSourceResolver().apply {
                    sources[1L to 0] = ResolvedSource.Http(server.url("/expired").toString(), emptyMap(), item.cacheKey)
                    sources[1L to 1] = ResolvedSource.Http(server.url("/renewed").toString(), emptyMap(), item.cacheKey)
                }
            val cache = SpanCache(directory)
            val client = testMediaClient()
            engine(resolver) { resolved ->
                val source = resolved as ResolvedSource.Http
                HttpByteSource(client, source.url, source.headers, cache, source.cacheKey)
            }.use { engine ->
                engine.setWindow(listOf(item), 0, 700)
                awaitReady(engine)
                assertEquals(700L, engine.positionMs())
                synchronized(resolver) { assertEquals(listOf(item to 0, item to 1), resolver.calls) }
            }
            assertEquals("/expired", server.takeRequest().url.encodedPath)
            assertEquals("/renewed", server.takeRequest().url.encodedPath)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun engine(
        resolver: DesktopSourceResolver = resolver(),
        rate: Int = RATE,
        open: (ResolvedSource) -> ByteSource = { FileByteSource((it as ResolvedSource.File).path) },
    ) = FfAudioEngine(nativeDirectory("ffmpeg"), nativeDirectory("ndmedia"), resolver, open, EngineBackend.NULL, rate)

    private fun resolver(file: Path = media()) =
        FakeDesktopSourceResolver().apply { sources[1L to 0] = ResolvedSource.File(file) }

    private fun media() =
        Path.of(requireNotNull(System.getProperty("neutrodyne.moduleDir")), "src/test/resources/media/wav_2s.wav")

    private fun period(engine: FfAudioEngine) =
        requireNotNull(
            Json.parseToJsonElement(requireNotNull(engine.outputDescription)).jsonObject["period"],
        ).jsonPrimitive.int

    private fun awaitReady(engine: FfAudioEngine) =
        await("ready engine") {
            assertHealthy(engine)
            engine.state.value is EngineState.Ready
        }

    private fun assertHealthy(engine: FfAudioEngine) {
        check(engine.state.value !is EngineState.Error) { "engine failed: ${engine.state.value}" }
    }

    private fun await(
        description: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(2)
        assertTrue(condition(), "timed out waiting for $description")
    }

    private fun nativeDirectory(kind: String): Path {
        val target =
            when {
                System.getProperty("os.name").startsWith("Windows") -> "windows-x64"
                System.getProperty("os.name") == "Mac OS X" -> "macos-arm64"
                System.getProperty("os.arch") in setOf("aarch64", "arm64") -> "linux-arm64"
                else -> "linux-x64"
            }
        return Path.of(requireNotNull(System.getProperty("neutrodyne.rootDir")), "playback/native/build/$kind/$target")
    }

    private class BlockedSource(
        bytes: ByteArray,
    ) : ByteSource {
        private val delegate = MemoryByteSource(bytes)
        val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        val aborted = AtomicBoolean(false)
        override val length get() = delegate.length

        override fun read(
            position: Long,
            dst: MemorySegment,
            maxBytes: Int,
        ): Int {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            return if (aborted.get()) -1 else delegate.read(position, dst, maxBytes)
        }

        override fun abort() {
            aborted.set(true)
            release.countDown()
        }

        override fun close() = abort()
    }

    private companion object {
        const val RATE = 44_100
    }
}
