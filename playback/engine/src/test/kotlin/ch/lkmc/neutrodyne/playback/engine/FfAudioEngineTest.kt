// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import ch.lkmc.neutrodyne.core.testing.FakeDesktopSourceResolver
import ch.lkmc.neutrodyne.playback.native.AudioOutput
import ch.lkmc.neutrodyne.playback.native.NdOutputException
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
            // The MD1b prepare-ahead resolves the next item, but a tail edit must
            // never resolve or restart the item the user hears.
            synchronized(resolver) {
                assertEquals(listOf(item to 0), resolver.calls.filter { it.first.episodeId == 1L })
            }
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

    /**
     * MD1 AC4 with the null back-end: the next window item is prepared while the
     * current one still plays, the handover keeps the device running with no gap
     * longer than 50 ms, the transition event carries the audible boundary, and
     * the last item still ends the engine.
     */
    @Test
    fun automaticTransitionPreparesTheNextItemAndKeepsTheRingContinuous() {
        val second = EngineItem(2, "ep:2:pin", true)
        val resolver = resolver().apply { sources[2L to 0] = ResolvedSource.File(media()) }
        engine(resolver).use { engine ->
            val transitions = mutableListOf<EngineEvent.ItemTransition>()
            val scope = CoroutineScope(Dispatchers.Default)
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                engine.events.filterIsInstance<EngineEvent.ItemTransition>().collect { transitions += it }
            }
            try {
                engine.setWindow(listOf(item, second), 0, null)
                engine.play()
                awaitReady(engine)
                // The next item is prepared before the current one ends.
                await("next item prepared") {
                    synchronized(resolver) { resolver.calls.any { it.first.episodeId == 2L } }
                }
                assertTrue(engine.positionMs() < 2_000)
                val framesBefore = requireNotNull(engine.outputFramesPlayed).invoke()
                val underrunBefore = requireNotNull(engine.outputUnderrunFrames).invoke()
                await("automatic transition") { transitions.isNotEmpty() }
                val event = transitions.single()
                assertEquals(1L, event.fromEpisodeId)
                assertEquals(2L, event.toEpisodeId)
                assertEquals(TransitionReason.AUTO, event.reason)
                assertTrue(
                    abs(event.fromPositionMs - 2_000) <= 50,
                    "outgoing position ${event.fromPositionMs}",
                )
                // The device never stopped: it kept consuming frames across the
                // boundary and the ring never emptied for longer than 50 ms.
                val framesAfter = requireNotNull(engine.outputFramesPlayed).invoke()
                assertTrue(framesAfter > framesBefore, "the device stopped at the transition")
                val gapMs = (requireNotNull(engine.outputUnderrunFrames).invoke() - underrunBefore) * 1_000 / RATE
                assertTrue(gapMs <= 50, "transition gap $gapMs ms")
                // The clock follows the arrival from its first audible frame.
                await("arrival position") { engine.positionMs() < 1_000 }
                await("end of the second item") { engine.state.value == EngineState.Ended }
                assertTrue(abs(engine.positionMs() - 2_000) <= 50)
            } finally {
                scope.cancel()
            }
        }
    }

    /** The next item's preparation starts about 10 s before the end, not at the start. */
    @Test
    fun theNextItemIsPreparedAboutTenSecondsBeforeTheEnd() {
        val first = EngineItem(1, "ep:1:pin", true)
        val second = EngineItem(2, "ep:2:pin", true)
        val file = writeTestWave(ByteArray(15 * RATE * 4))
        try {
            val resolver =
                FakeDesktopSourceResolver().apply {
                    sources[1L to 0] = ResolvedSource.File(file)
                    sources[2L to 0] = ResolvedSource.File(media())
                }
            engine(resolver).use { engine ->
                engine.setSpeed(3f)
                engine.setWindow(listOf(first, second), 0, null)
                engine.play()
                awaitReady(engine)
                await("next item prepared") {
                    synchronized(resolver) { resolver.calls.any { it.first.episodeId == 2L } }
                }
                val remainingMs = 15_000 - engine.positionMs()
                assertTrue(
                    remainingMs in 8_000..12_000,
                    "prepared with ${remainingMs}ms remaining at ${engine.positionMs()}ms",
                )
                await("end of the first item") { engine.state.value == EngineState.Ended }
            }
        } finally {
            Files.delete(file)
        }
    }

    @Test
    fun aTailEditDuringPreparationDropsTheStalePrepare() {
        val second = EngineItem(2, "ep:2:pin", true)
        val third = EngineItem(3, "ep:3:pin", true)
        val resolver =
            resolver().apply {
                sources[2L to 0] = ResolvedSource.File(media())
                sources[3L to 0] = ResolvedSource.File(media())
            }
        engine(resolver).use { engine ->
            val transitions = mutableListOf<EngineEvent.ItemTransition>()
            val scope = CoroutineScope(Dispatchers.Default)
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                engine.events.filterIsInstance<EngineEvent.ItemTransition>().collect { transitions += it }
            }
            try {
                engine.setWindow(listOf(item, second), 0, null)
                engine.play()
                awaitReady(engine)
                await("next item prepared") {
                    synchronized(resolver) { resolver.calls.any { it.first.episodeId == 2L } }
                }
                // The queued item is replaced while its preparation is in flight.
                engine.updateWindow(
                    EngineWindowDiff(
                        removed = setOf(2),
                        inserted = listOf(PlacedItem(1, third)),
                    ),
                )
                await("transition to the replacement") { transitions.isNotEmpty() }
                assertEquals(3L, transitions.single().toEpisodeId)
                await("end of the replacement") { engine.state.value == EngineState.Ended }
            } finally {
                scope.cancel()
            }
        }
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
        output: (() -> AudioOutput)? = null,
        open: (ResolvedSource) -> ByteSource = { FileByteSource((it as ResolvedSource.File).path) },
    ): FfAudioEngine {
        val directory = nativeDirectory("ffmpeg")
        val ndmedia = nativeDirectory("ndmedia")
        return if (output == null) {
            FfAudioEngine(directory, ndmedia, resolver, open, EngineBackend.NULL, rate)
        } else {
            FfAudioEngine(directory, ndmedia, resolver, open, EngineBackend.NULL, rate, output)
        }
    }

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
        timeoutMs: Long = 10_000L,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
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

    @Test
    fun aRepeatedContentChangeMapsToTheContentChangedError() {
        val failing =
            object : ByteSource {
                override val length: Long? get() = 100

                override fun read(
                    position: Long,
                    dst: MemorySegment,
                    maxBytes: Int,
                ): Int = throw ContentChangedException("the representation changed")

                override fun abort() = Unit

                override fun close() = Unit
            }
        val resolver =
            FakeDesktopSourceResolver().apply {
                sources[1L to 0] = ResolvedSource.File(media())
                sources[1L to 1] = ResolvedSource.File(media())
            }
        engine(resolver, open = { failing }).use { engine ->
            engine.setWindow(listOf(item), 0, null)
            engine.play()
            await("content changed error") { engine.state.value is EngineState.Error }
            assertEquals(
                EngineError.ContentChanged,
                (engine.state.value as EngineState.Error).error,
            )
            // The first failure re-resolves once; the second maps to the error.
            synchronized(resolver) { assertEquals(listOf(item to 0, item to 1), resolver.calls) }
        }
    }

    @Test
    fun anOutputWriteFailurePublishesDeviceLostAndDiscardsTheOutput() {
        val failing = FakeOutput(FailOn.WRITE, NdOutputException.E_DEVICE)
        engine(output = { failing }).use { engine ->
            val lost = CompletableFuture<EngineEvent.DeviceLost>()
            val scope = CoroutineScope(Dispatchers.Default)
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                engine.events.filterIsInstance<EngineEvent.DeviceLost>().collect { lost.complete(it) }
            }
            try {
                engine.setWindow(listOf(item), 0, null)
                engine.play()
                await("output error") { engine.state.value is EngineState.Error }
                val error = engine.state.value as EngineState.Error
                assertEquals(EngineError.Output("nd_out_write failed with -4"), error.error)
                assertEquals(EngineEvent.DeviceLost, lost.get(5, TimeUnit.SECONDS))
                assertTrue(failing.closed, "the failed output must be discarded")
                // No stale output reference: a new window opens a fresh device.
                engine.setWindow(listOf(item), 0, null)
                await("the engine stays erroring on the discarded output") {
                    engine.state.value is EngineState.Error
                }
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun anOutputStartFailureBecomesAnErrorInsteadOfEscapingPlay() {
        val failing = FakeOutput(FailOn.START, NdOutputException.E_DEVICE)
        engine(output = { failing }).use { engine ->
            engine.setWindow(listOf(item), 0, null)
            engine.play()
            await("output error") { engine.state.value is EngineState.Error }
            assertTrue(failing.closed)
        }
    }

    @Test
    fun anOutputOpenFailureBecomesAnError() {
        engine(output = {
            throw NdOutputException("nd_out_open failed with -4", NdOutputException.E_DEVICE)
        }).use { engine ->
            engine.setWindow(listOf(item), 0, null)
            engine.play()
            await("output error") { engine.state.value is EngineState.Error }
            assertEquals(
                EngineError.Output("nd_out_open failed with -4"),
                (engine.state.value as EngineState.Error).error,
            )
        }
    }

    @Test
    fun aRemovedDeviceIsDiscardedAndTheNextPlayReopens() {
        engine().use { engine ->
            engine.setWindow(listOf(item), 0, null)
            engine.play()
            awaitReady(engine)
            engine.onDeviceRemoved()
            await("output error") { engine.state.value is EngineState.Error }
            assertEquals(
                EngineError.Output("the output device was removed"),
                (engine.state.value as EngineState.Error).error,
            )
            // The next play opens a fresh default device and plays again.
            engine.setWindow(listOf(item), 0, null)
            awaitReady(engine)
        }
    }

    /** The engine's output seam, driven by the fault tests. */
    private class FakeOutput(
        private val failOn: FailOn? = null,
        private val code: Int = NdOutputException.E_DEVICE,
    ) : AudioOutput {
        var writes = 0
            private set
        var starts = 0
            private set
        var closed = false
            private set
        private var played = 0L

        override val framesPlayed: Long get() = played
        override val underrunFrames: Long get() = 0

        override fun freeFrames(): Int = 4_096

        override fun write(
            pcm: MemorySegment,
            frames: Int,
        ): Int {
            writes++
            if (failOn == FailOn.WRITE) throw NdOutputException("nd_out_write failed with $code", code)
            played += frames
            return frames
        }

        override fun start(): Int {
            starts++
            if (failOn == FailOn.START) throw NdOutputException("nd_out_start failed with $code", code)
            return 0
        }

        override fun stop(): Int = 0

        override fun clear(): Int = 0

        override fun describe(): String =
            """{"rate":44100,"channels":2,"backend":"fake","device":"fake","period":1024}"""

        override fun clockReader(): () -> Long = { played }

        override fun underrunReader(): () -> Long = { 0L }

        override fun close() {
            closed = true
        }
    }

    private enum class FailOn { WRITE, START }

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
