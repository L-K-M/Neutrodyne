// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import ch.lkmc.neutrodyne.playback.native.NdOutput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * MD0 single-local-item instrument. The worker owns decode, DSP and output
 * lifetime. Callers serialize device controls with publication/cleanup; abort
 * reaches an opening source before waiting for that worker. MD1 supplies the
 * production queue/controller and asynchronous preparation.
 */
class Md0AudioProbe(
    private val ffmpegDirectory: Path,
    private val ndmediaDirectory: Path,
    private val backend: String?,
    private val deviceRate: Int,
    private val openSource: (Path) -> ByteSource = ::FileChannelByteSource,
) : AutoCloseable {
    private data class Item(
        val path: Path,
        val speed: Float,
        val skipSilence: Boolean,
    )

    private val commands = LinkedBlockingQueue<Item>()
    private val control = Any()
    private val closed = AtomicBoolean(false)
    private val submitted = AtomicBoolean(false)
    private val ended = AtomicBoolean(false)
    private val failure = AtomicReference<Throwable?>(null)
    private var wantPlaying = false
    private var source: ByteSource? = null
    private var activeOutput: NdOutput? = null

    @Volatile private var output: NdOutput? = null

    @Volatile private var clock: TimelineClock? = null

    @Volatile var outputDescription: String? = null
        private set

    @Volatile var durationMs: Long? = null
        private set

    private val thread =
        Thread({ run() }, "nd-engine").apply {
            isDaemon = true
            start()
        }

    val isEnded: Boolean get() {
        checkFailure()
        return ended.get()
    }
    val framesPlayed: Long get() = output?.framesPlayed ?: 0
    val underrunFrames: Long get() = output?.underrunFrames ?: 0

    fun play(
        path: Path,
        speed: Float,
        skipSilence: Boolean,
    ): Boolean =
        synchronized(control) {
            check(!closed.get()) { "engine is closed" }
            require(speed.isFinite() && speed in MIN_SPEED..MAX_SPEED)
            check(submitted.compareAndSet(false, true)) { "the MD0 instrument plays one item" }
            wantPlaying = true
            commands.offer(Item(path, speed, skipSilence))
        }

    fun pause() =
        synchronized(control) {
            wantPlaying = false
            activeOutput?.stop()
            clock?.pause()
        }

    fun resume() =
        synchronized(control) {
            check(!closed.get()) { "engine is closed" }
            checkFailure()
            wantPlaying = true
            clock?.resume()
            activeOutput?.start()
        }

    fun positionMs(): Long {
        checkFailure()
        return clock?.positionMs() ?: 0
    }

    fun awaitEnded(timeoutMs: Long): Boolean {
        require(timeoutMs >= 0)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!isEnded && System.nanoTime() < deadline) Thread.sleep(10)
        return isEnded
    }

    override fun close() {
        synchronized(control) {
            if (!closed.compareAndSet(false, true)) return
            source?.abort()
            activeOutput?.stop()
        }
        // Wake a worker waiting for its first item without interrupting a native
        // read: source.abort is the AVIO cancellation mechanism.
        thread.interrupt()
        thread.join(CLOSE_JOIN_MS)
        check(!thread.isAlive) { "engine did not stop after source abort; native storage remains owned by worker" }
    }

    private fun checkFailure() {
        failure.get()?.let { throw IllegalStateException("MD0 engine failed", it) }
    }

    private fun run() {
        try {
            val item = commands.take()
            if (closed.get()) return
            playItem(item)
        } catch (problem: Throwable) {
            if (!closed.get()) failure.set(problem)
        }
    }

    private fun playItem(item: Item) {
        openSource(item.path).use { input ->
            synchronized(control) {
                if (closed.get()) {
                    input.abort()
                    return
                }
                source = input
            }
            try {
                FfmpegPipeline.open(input, ffmpegDirectory).use { pipeline -> playPipeline(item, pipeline) }
            } finally {
                synchronized(control) { source = null }
            }
        }
    }

    private fun playPipeline(
        item: Item,
        pipeline: FfmpegPipeline,
    ) {
        durationMs = pipeline.demuxer.durationMs
        val decoder = pipeline.decoder
        val skip =
            SilenceSkipper().takeIf { item.skipSilence }?.apply {
                configure(decoder.sampleRate, decoder.channels)
            }
        val sonic = Sonic(decoder.sampleRate, decoder.channels, item.speed, 1f, deviceRate)
        val gain = GainStage()
        NdOutput.open(ndmediaDirectory, backend, deviceRate, decoder.channels, RING_MS).use { out ->
            val description = out.describe()
            val period = requireNotNull(Json.parseToJsonElement(description).jsonObject["period"]).jsonPrimitive.int
            val timeline = TimelineClock(deviceRate, period.toLong(), out.clockReader())
            var outputFrames = 0L
            var inputFrames = 0L
            var retainedInputFrames = 0L
            var firstPtsUs: Long? = null
            val buffer = ByteBuffer.allocate(FRAME_BYTES).order(ByteOrder.nativeOrder())
            synchronized(control) {
                if (closed.get()) return
                output = out
                activeOutput = out
                clock = timeline
                outputDescription = description
                if (wantPlaying) out.start() else timeline.pause()
            }
            try {
                fun pumpSonic() {
                    while (sonic.outputSize > 0 && !closed.get()) {
                        buffer.clear()
                        sonic.getOutput(buffer)
                        buffer.flip()
                        val frames = buffer.remaining() / (decoder.channels * Short.SIZE_BYTES)
                        if (frames == 0) break
                        val pcm = ShortArray(frames * decoder.channels) { buffer.getShort() }
                        gain.process(pcm, 0, frames, decoder.channels)
                        // The ring copies each block. Release native conversion
                        // storage immediately, rather than accumulating a soak's PCM.
                        Arena.ofConfined().use { arena ->
                            val segment = arena.allocate(pcm.size.toLong() * Float.SIZE_BYTES)
                            pcm.forEachIndexed { index, sample ->
                                segment.set(ValueLayout.JAVA_FLOAT, index.toLong() * Float.SIZE_BYTES, sample / 32768f)
                            }
                            var written = 0
                            while (written < frames && !closed.get()) {
                                val accepted =
                                    synchronized(control) {
                                        if (!wantPlaying || closed.get()) {
                                            0
                                        } else {
                                            out.write(
                                                segment.asSlice(written.toLong() * decoder.channels * Float.SIZE_BYTES),
                                                frames - written,
                                            )
                                        }
                                    }
                                written += accepted
                                if (accepted == 0) Thread.sleep(RING_POLL_MS)
                            }
                            outputFrames += written
                        }
                    }
                }

                fun queueSonic(
                    input: ByteBuffer,
                    sourceFrame: Long,
                ) {
                    val frames = input.remaining() / (decoder.channels * Short.SIZE_BYTES)
                    if (frames == 0) return
                    timeline.push(
                        TimelineClock.Marker(
                            (retainedInputFrames * deviceRate / (decoder.sampleRate * item.speed.toDouble())).toLong(),
                            item.path.fileName.toString(),
                            (firstPtsUs ?: 0L) + sourceFrame * MICROS_PER_SECOND / decoder.sampleRate,
                            item.speed,
                        ),
                    )
                    retainedInputFrames += frames
                    sonic.queueInput(input)
                    pumpSonic()
                }

                fun drainSkip() {
                    while (skip?.hasOutput() == true) {
                        buffer.clear()
                        val sourceFrame = requireNotNull(skip.getOutput(buffer))
                        buffer.flip()
                        queueSonic(buffer, sourceFrame)
                    }
                }

                fun accept(frame: AudioFrame) {
                    if (frame.samples.isEmpty()) return
                    if (firstPtsUs == null) firstPtsUs = frame.ptsUs
                    val sourceFrame = inputFrames
                    inputFrames += frame.samples.size / frame.channels
                    val input =
                        ByteBuffer
                            .allocate(
                                frame.samples.size * Short.SIZE_BYTES,
                            ).order(ByteOrder.nativeOrder())
                    input.asShortBuffer().put(frame.samples)
                    if (skip == null) {
                        queueSonic(input, sourceFrame)
                    } else {
                        skip.queueInput(input)
                        drainSkip()
                    }
                    pumpSonic()
                }
                while (!closed.get()) {
                    val packet = pipeline.demuxer.readPacket() ?: break
                    check(decoder.send(packet)) { "decoder backpressure after its output was drained" }
                    while (true) accept(decoder.receive() ?: break)
                }
                if (closed.get()) return
                decoder.flush()
                while (true) accept(decoder.receive() ?: break)
                decoder.drainResampler()?.let(::accept)
                skip?.queueEndOfStream()
                drainSkip()
                sonic.queueEndOfStream()
                pumpSonic()
                var progress = out.framesPlayed
                var lastProgress = System.nanoTime()
                while (out.framesPlayed < outputFrames && !closed.get()) {
                    val playing = synchronized(control) { wantPlaying }
                    val now = System.nanoTime()
                    val played = out.framesPlayed
                    if (!playing || played != progress) {
                        progress = played
                        lastProgress = now
                    }
                    check(now - lastProgress < DRAIN_WAIT_NS) { "device stopped consuming queued PCM" }
                    Thread.sleep(RING_POLL_MS)
                }
                if (!closed.get()) {
                    synchronized(control) {
                        out.stop()
                        timeline.resume()
                        timeline.finish((firstPtsUs ?: 0L) + inputFrames * MICROS_PER_SECOND / decoder.sampleRate)
                        ended.set(true)
                    }
                }
            } finally {
                synchronized(control) {
                    activeOutput = null
                    out.stop()
                }
            }
        }
    }

    private companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3f
        const val RING_MS = 200
        const val FRAME_BYTES = 1 shl 20
        const val MICROS_PER_SECOND = 1_000_000L
        const val RING_POLL_MS = 2L
        const val CLOSE_JOIN_MS = 5_000L
        const val DRAIN_WAIT_NS = 3_000_000_000L
    }
}
