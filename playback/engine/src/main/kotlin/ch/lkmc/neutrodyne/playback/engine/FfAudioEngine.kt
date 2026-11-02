// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import ch.lkmc.neutrodyne.playback.native.NdOutput
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The MD0 engine-thread prototype (11 Engine thread): one thread runs
 * decode → DSP → ring → device for one item; commands arrive through a
 * nonblocking queue so a blocked source read can still be interrupted by
 * close. The position readers see comes from [TimelineClock] on the
 * device-confirmed frame counter.
 *
 * This is the spike's proof of concept, not the MD1 controller: it plays a
 * single local file once and exposes `positionMs`, `ended` and the underrun
 * counter for the AC3 measurement.
 */
class FfAudioEngine(
    private val ffmpegDirectory: Path,
    private val ndmediaDirectory: Path,
    private val backend: String?,
    private val deviceRate: Int,
    private val openSource: (Path) -> ByteSource = FileByteSource::open,
) : AutoCloseable {
    private sealed interface Command {
        data class Play(
            val path: Path,
            val speed: Float,
            val skipSilence: Boolean,
        ) : Command

        data object Close : Command
    }

    private val commands = LinkedBlockingQueue<Command>()
    private val closed = AtomicBoolean(false)
    private val ended = AtomicBoolean(false)
    private val wantPlaying = AtomicBoolean(false)
    private val outFramesWritten = AtomicLong(0)

    // Cross-thread: callers pause/resume while the engine thread owns the fields.
    @Volatile private var output: NdOutput? = null

    @Volatile private var clock: TimelineClock? = null
    private val thread =
        Thread({ run() }, "nd-engine").apply {
            isDaemon = true
            start()
        }

    @Volatile var durationMs: Long? = null
        private set

    val isEnded: Boolean get() = ended.get()

    val outputDescription: String? get() = output?.describe()

    val framesPlayed: Long get() = output?.framesPlayed ?: 0

    val underrunFrames: Long get() = output?.underrunFrames ?: 0

    fun play(
        path: Path,
        speed: Float,
        skipSilence: Boolean,
    ) = commands.offer(Command.Play(path, speed, skipSilence))

    /**
     * Pause and resume act on the device directly: commands queued during a
     * playItem decode loop would sit unprocessed until the item ends.
     */
    fun pause() {
        wantPlaying.set(false)
        output?.stop()
    }

    fun resume() {
        wantPlaying.set(true)
        output?.start()
    }

    fun positionMs(): Long = clock?.positionMs() ?: 0

    /** Blocks until the item ends or [timeoutMs] passes; returns which. */
    fun awaitEnded(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!ended.get() && System.nanoTime() < deadline) Thread.sleep(10)
        return ended.get()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        commands.offer(Command.Close)
        thread.join(CLOSE_JOIN_MS)
        output?.close()
    }

    private fun run() {
        loop@ while (!closed.get()) {
            when (val command = commands.take()) {
                is Command.Play -> {
                    wantPlaying.set(true)
                    playItem(command)
                }

                Command.Close -> {
                    break@loop
                }
            }
        }
    }

    private fun playItem(command: Command.Play) {
        // The pipeline does not own the source; close the channel ourselves.
        openSource(command.path).use { source ->
            FfmpegPipeline.open(source, ffmpegDirectory).use { pipeline ->
                durationMs = pipeline.demuxer.durationMs
                val decoder = pipeline.decoder
                val demuxer = pipeline.demuxer
                val skip =
                    SilenceSkipper().takeIf { command.skipSilence }?.apply {
                        configure(decoder.sampleRate, decoder.channels)
                    }
                val sonic = Sonic(decoder.sampleRate, decoder.channels, command.speed, 1f, deviceRate)
                val gain = GainStage()
                val out = NdOutput.open(ndmediaDirectory, backend, deviceRate, decoder.channels, RING_MS)
                output = out
                val latency = LATENCY_MS.toLong() * deviceRate / 1000
                val tl = TimelineClock(deviceRate, latency) { out.framesPlayed }
                clock = tl
                tl.clear()
                out.start()

                val arena = Arena.ofShared()
                val inBuffer = ByteBuffer.allocate(FRAME_BYTES).order(ByteOrder.nativeOrder())
                val outBuffer = ByteBuffer.allocate(FRAME_BYTES).order(ByteOrder.nativeOrder())
                var mediaUs = 0L
                var firstPtsSeen = false
                var eos = false

                fun pumpSonic() {
                    while (sonic.outputSize > 0) {
                        outBuffer.clear()
                        sonic.getOutput(outBuffer)
                        outBuffer.flip()
                        val frames = outBuffer.remaining() / (decoder.channels * 2)
                        if (frames == 0) break
                        val pcm = ShortArray(frames * decoder.channels)
                        repeat(pcm.size) { pcm[it] = outBuffer.getShort() }
                        gain.process(pcm, 0, frames, decoder.channels)
                        val seg = arena.allocate(frames.toLong() * decoder.channels * 4)
                        for (i in pcm.indices) {
                            seg.set(ValueLayout.JAVA_FLOAT, i * 4L, pcm[i] / 32768f)
                        }
                        var written = 0
                        while (written < frames && !closed.get()) {
                            while (!wantPlaying.get() && !closed.get()) Thread.sleep(PAUSE_POLL_MS)
                            // nd_out_write may accept only part of the block; the
                            // slice hands it the unwritten remainder next round.
                            val accepted =
                                out.write(
                                    seg.asSlice(written.toLong() * decoder.channels * BYTES_PER_F32),
                                    frames - written,
                                )
                            if (accepted == 0) Thread.sleep(RING_POLL_MS)
                            written += accepted
                        }
                        tl.push(
                            TimelineClock.Marker(
                                outFramesWritten.get(),
                                command.path.fileName.toString(),
                                mediaUs,
                                command.speed,
                            ),
                        )
                        outFramesWritten.addAndGet(frames.toLong())
                    }
                }

                while (!eos && !closed.get()) {
                    val packet = demuxer.readPacket()
                    if (packet == null) {
                        decoder.flush()
                        eos = true
                    } else {
                        if (!decoder.send(packet)) continue
                    }
                    while (true) {
                        val frame = decoder.receive() ?: break
                        if (frame.samples.isEmpty()) continue
                        if (!firstPtsSeen && frame.ptsUs != null) {
                            mediaUs = frame.ptsUs
                            firstPtsSeen = true
                        }
                        inBuffer.clear()
                        val sb = inBuffer.asShortBuffer()
                        sb.put(frame.samples)
                        inBuffer.limit(frame.samples.size * 2)
                        if (skip != null) {
                            skip.queueInput(inBuffer.duplicate().order(ByteOrder.nativeOrder()))
                            val out = ByteBuffer.allocate(FRAME_BYTES).order(ByteOrder.nativeOrder())
                            skip.getOutput(out)
                            out.flip()
                            if (out.hasRemaining()) sonic.queueInput(out)
                        } else {
                            sonic.queueInput(inBuffer)
                        }
                        mediaUs += frame.samples.size.toLong() * 1_000_000 /
                            (decoder.channels * decoder.sampleRate)
                        pumpSonic()
                    }
                }
                if (skip != null) {
                    skip.queueEndOfStream()
                    val tail = ByteBuffer.allocate(FRAME_BYTES).order(ByteOrder.nativeOrder())
                    skip.getOutput(tail)
                    tail.flip()
                    if (tail.hasRemaining()) sonic.queueInput(tail)
                }
                sonic.queueEndOfStream()
                pumpSonic()
                // Wait for the ring to drain so position reaches the media end.
                val deadline = System.nanoTime() + DRAIN_WAIT_NS
                while (out.freeFrames() < RING_MS * deviceRate / 1000 &&
                    System.nanoTime() < deadline && !closed.get()
                ) {
                    Thread.sleep(RING_POLL_MS)
                }
                out.stop()
                tl.finish(mediaUs)
                ended.set(true)
                arena.close()
            }
        }
    }

    private companion object {
        const val RING_MS = 200
        const val LATENCY_MS = 30
        const val BYTES_PER_F32 = 4L
        const val FRAME_BYTES = 1 shl 20
        const val PAUSE_POLL_MS = 20L
        const val RING_POLL_MS = 2L
        const val CLOSE_JOIN_MS = 5_000L
        const val DRAIN_WAIT_NS = 3_000_000_000L
    }
}

/** Opens a corpus file as a [ByteSource] (the spike uses local files only). */
internal object FileByteSource {
    fun open(path: Path): ByteSource =
        object : ByteSource {
            private val channel =
                java.nio.channels.FileChannel
                    .open(path)
            private val aborted = AtomicBoolean(false)

            override val length: Long? get() = channel.size()

            override fun read(
                position: Long,
                dst: java.lang.foreign.MemorySegment,
                maxBytes: Int,
            ): Int {
                if (aborted.get()) return -1
                val buffer = ByteBuffer.allocate(maxBytes)
                val read = channel.read(buffer, position)
                if (read <= 0) return 0
                buffer.flip()
                dst.asSlice(0, read.toLong()).copyFrom(
                    java.lang.foreign.MemorySegment
                        .ofBuffer(buffer),
                )
                return read
            }

            override fun abort() = aborted.set(true)

            override fun close() = channel.close()
        }
}
