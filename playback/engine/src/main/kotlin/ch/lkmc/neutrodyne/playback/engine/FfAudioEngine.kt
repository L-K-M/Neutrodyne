// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import ch.lkmc.neutrodyne.playback.native.NdOutput
import ch.lkmc.neutrodyne.playback.native.NdOutputException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Device selection is explicit in tests: the null backend never opens audio hardware. */
enum class EngineBackend(
    internal val nativeName: String?,
) {
    SYSTEM(null),
    NULL("null"),
}

/**
 * One engine thread owns native decode, DSP and output lifetime. Preparation
 * transfers a shared-arena pipeline from nd-prepare to nd-engine exactly once.
 * The control lock covers publication and short device calls, never native I/O.
 */
class FfAudioEngine internal constructor(
    private val ffmpegDirectory: Path,
    private val ndmediaDirectory: Path,
    private val resolver: DesktopSourceResolver,
    private val openSource: (ResolvedSource) -> ByteSource,
    private val backend: EngineBackend = EngineBackend.SYSTEM,
    private val deviceRate: Int = DEFAULT_RATE,
) : AudioEngine {
    constructor(
        ffmpegDirectory: Path,
        ndmediaDirectory: Path,
        resolver: DesktopSourceResolver,
        clients: NetworkClients,
        cache: SpanCache,
        backend: EngineBackend = EngineBackend.SYSTEM,
        deviceRate: Int = DEFAULT_RATE,
    ) : this(
        ffmpegDirectory,
        ndmediaDirectory,
        resolver,
        { source ->
            when (source) {
                is ResolvedSource.File -> FileByteSource(source.path)
                else -> HttpByteSource.open(clients, source, cache)
            }
        },
        backend,
        deviceRate,
    )

    init {
        require(deviceRate > 0)
    }

    private data class SilenceJump(
        val outFrame: Long,
        val event: EngineEvent.Discontinuity,
    )

    private val silenceJumps = ArrayDeque<SilenceJump>()

    private data class Request(
        val generation: Long,
        val item: EngineItem,
        val positionMs: Long,
        val attempt: Int = 0,
    )

    private data class Settings(
        val speed: Float = 1f,
        val skipSilence: Boolean = false,
        val volume: Float = 1f,
        val fade: Float = 1f,
    )

    private class Prepared(
        val source: ByteSource,
        val pipeline: FfmpegPipeline,
    ) : AutoCloseable {
        override fun close() {
            try {
                pipeline.close()
            } finally {
                source.close()
            }
        }
    }

    private val mutableState = MutableStateFlow<EngineState>(EngineState.Idle)
    private val mutableEvents = MutableSharedFlow<EngineEvent>(extraBufferCapacity = EVENT_CAPACITY)
    override val state = mutableState.asStateFlow()
    override val events = mutableEvents.asSharedFlow()
    private val control = Any()
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val commands = ConcurrentLinkedQueue<Request>()
    private val settings = AtomicReference(Settings())
    private var window = emptyList<EngineItem>()
    private var current: EngineItem? = null
    private var wantPlaying = false
    private var source: ByteSource? = null
    private var resolveJob: Job? = null
    private var output: NdOutput? = null
    private var ownedOutput: NdOutput? = null
    private var outputReady = false
    private var queuedFrames = 0L
    private var emptySinceNs: Long? = null
    private var sessionBaseFrames = 0L
    private var sessionPeriodFrames = 0L
    private val anchorMs = AtomicLong(0)

    @Volatile private var clock: TimelineClock? = null

    @Volatile internal var outputDescription: String? = null
        private set

    @Volatile internal var outputFramesPlayed: (() -> Long)? = null
        private set
    private val prepare =
        Executors.newSingleThreadScheduledExecutor { action ->
            Thread(action, "nd-prepare").apply { isDaemon = true }
        }

    init {
        prepare.scheduleWithFixedDelay(::monitorBuffer, BUFFER_POLL_MS, BUFFER_POLL_MS, TimeUnit.MILLISECONDS)
    }

    private val thread =
        Thread({ run() }, "nd-engine").apply {
            isDaemon = true
            start()
        }

    override fun setWindow(
        items: List<EngineItem>,
        currentIndex: Int,
        startPositionMs: Long?,
    ) {
        require(items.map { it.episodeId }.distinct().size == items.size) { "duplicate episode in engine window" }
        require(if (items.isEmpty()) currentIndex == 0 else currentIndex in items.indices)
        require(startPositionMs == null || startPositionMs >= 0)
        synchronized(control) {
            checkOpen()
            window = items.toList()
            current = items.getOrNull(currentIndex)
            replaceRequest(startPositionMs ?: 0)
            if (current == null) mutableState.value = EngineState.Idle
        }
    }

    override fun updateWindow(diff: EngineWindowDiff) =
        synchronized(control) {
            checkOpen()
            window = diff.applyTo(window, current?.episodeId)
        }

    override fun play() =
        synchronized(control) {
            checkOpen()
            wantPlaying = true
            applyDesiredState()
        }

    override fun pause() =
        synchronized(control) {
            checkOpen()
            wantPlaying = false
            applyDesiredState()
        }

    override fun seekTo(positionMs: Long) {
        require(positionMs >= 0)
        synchronized(control) {
            checkOpen()
            if (current == null) return
            val from = positionMs()
            replaceRequest(positionMs)
            val item = requireNotNull(current)
            emit(EngineEvent.Discontinuity(item.episodeId, DiscontinuityReason.SEEK, from, positionMs))
        }
    }

    override fun setSpeed(speed: Float) {
        require(speed.isFinite() && speed in MIN_SPEED..MAX_SPEED)
        updateSettings { it.copy(speed = speed) }
    }

    override fun setSkipSilence(enabled: Boolean) = updateSettings { it.copy(skipSilence = enabled) }

    override fun setVolume(linear: Float) {
        require(linear.isFinite() && linear in 0f..1f)
        updateSettings { it.copy(volume = linear) }
    }

    override fun setFade(factor: Float) {
        require(factor.isFinite() && factor in 0f..1f)
        updateSettings { it.copy(fade = factor) }
    }

    override fun positionMs(): Long = clock?.positionMs() ?: anchorMs.get()

    override fun close() {
        synchronized(control) {
            if (!closed.compareAndSet(false, true)) return
            generation.incrementAndGet()
            source?.abort()
            resolveJob?.cancel()
            wantPlaying = false
            output?.stop()
        }
        thread.join(CLOSE_WAIT_MS)
        prepare.shutdown()
        val preparedStopped = prepare.awaitTermination(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)
        check(!thread.isAlive && preparedStopped) { "engine did not stop after source cancellation" }
    }

    private fun updateSettings(change: (Settings) -> Settings) {
        checkOpen()
        settings.updateAndGet(change)
    }

    /** Caller holds control, so neither an old prepare nor a queued play can publish after this. */
    private fun replaceRequest(positionMs: Long) {
        val version = generation.incrementAndGet()
        source?.abort()
        resolveJob?.cancel()
        output?.stop()
        // A reader that sees the detached clock must already see this anchor.
        anchorMs.set(positionMs)
        clock = null
        outputReady = false
        silenceJumps.clear()
        current?.let {
            mutableState.value = EngineState.Buffering(it.episodeId)
            commands.offer(Request(version, it, positionMs))
        }
    }

    private fun applyDesiredState() {
        val out = output ?: return
        if (wantPlaying && outputReady) {
            clock?.resume()
            out.start()
        } else {
            out.stop()
            clock?.pause()
        }
        val ready = mutableState.value as? EngineState.Ready ?: return
        mutableState.value = ready.copy(playing = wantPlaying)
    }

    private fun run() {
        try {
            while (!closed.get()) {
                val request = commands.poll()
                if (request == null) {
                    Thread.sleep(POLL_MS)
                    continue
                }
                if (!isCurrent(request)) continue
                try {
                    val future = prepare.submit<Prepared> { prepare(request) }
                    // Even a superseded result is collected and closed. Cancellation
                    // interrupts its source/job, never an FFM call or its arena.
                    future.get().use { prepared ->
                        if (isCurrent(request)) playPipeline(request, prepared.pipeline)
                    }
                } catch (failure: Throwable) {
                    val problem = (failure as? java.util.concurrent.ExecutionException)?.cause ?: failure
                    synchronized(control) {
                        if (!isCurrent(request)) return@synchronized
                        if (request.attempt == 0 && shouldResolveAgain(problem)) {
                            val position = positionMs()
                            anchorMs.set(position)
                            clock = null
                            mutableState.value = EngineState.Buffering(request.item.episodeId)
                            commands.offer(request.copy(positionMs = position, attempt = 1))
                        } else {
                            mutableState.value = EngineState.Error(request.item.episodeId, mapError(problem))
                        }
                    }
                } finally {
                    synchronized(control) {
                        source = null
                        resolveJob = null
                    }
                }
            }
        } finally {
            synchronized(control) {
                output = null
                ownedOutput?.close()
                ownedOutput = null
            }
        }
    }

    private fun prepare(request: Request): Prepared =
        runBlocking {
            synchronized(control) {
                check(isCurrent(request)) { "superseded engine preparation" }
                resolveJob = coroutineContext.job
            }
            val resolved = resolver.resolve(request.item, request.attempt)
            if (resolved is ResolvedSource.YouTube) {
                val waitMs = (resolved.availableAtMs ?: 0) - System.currentTimeMillis()
                if (waitMs > SOURCE_WAIT_MS) throw IOException("YouTube stream availability exceeds 30 seconds")
                if (waitMs > 0) delay(waitMs)
            }
            val input = openSource(resolved)
            try {
                synchronized(control) {
                    if (!isCurrent(request)) input.abort()
                    check(isCurrent(request)) { "superseded engine source" }
                    source = input
                }
                val pipeline = FfmpegPipeline.open(input, ffmpegDirectory)
                try {
                    if (input is HttpByteSource) pipeline.demuxer.durationMs?.let(input::setDurationEstimate)
                    synchronized(control) {
                        coroutineContext.ensureActive()
                        check(isCurrent(request)) { "superseded prepared pipeline" }
                        // After handoff, cancellation must abort the source rather
                        // than cancel runBlocking and discard its native result.
                        resolveJob = null
                    }
                    Prepared(input, pipeline)
                } catch (failure: Throwable) {
                    pipeline.close()
                    throw failure
                }
            } catch (failure: Throwable) {
                input.close()
                throw failure
            }
        }

    private fun playPipeline(
        request: Request,
        pipeline: FfmpegPipeline,
    ) {
        val item = request.item
        val first = if (request.positionMs > 0) pipeline.seekFrame(request.positionMs) else null
        if (!isCurrent(request)) return
        val demuxer = pipeline.demuxer
        emit(EngineEvent.TracksKnown(item.episodeId, demuxer.durationMs, demuxer.chapters, demuxer.gapless))
        val out = ownedOutput ?: openOutput()
        run {
            out.stop()
            out.clear()
            val baseFrames = out.framesPlayed
            val rawClock = out.clockReader()
            val readFrames = { rawClock() - baseFrames }
            val description = out.describe()
            val period = requireNotNull(Json.parseToJsonElement(description).jsonObject["period"]).jsonPrimitive.int
            val timeline = TimelineClock(deviceRate, period.toLong(), readFrames)
            timeline.push(
                TimelineClock.Marker(
                    0,
                    item.episodeId.toString(),
                    request.positionMs * MICROS_PER_MS,
                    settings.get().speed,
                ),
            )
            synchronized(control) {
                if (!isCurrent(request)) return
                clock = timeline
                output = out
                outputReady = false
                queuedFrames = baseFrames
                sessionBaseFrames = baseFrames
                sessionPeriodFrames = period.toLong()
                silenceJumps.clear()
                emptySinceNs = null
                outputDescription = description
                outputFramesPlayed = readFrames
                if (!wantPlaying) timeline.pause()
            }
            var writtenFrames = 0L
            val initial = settings.get()
            val dsp =
                EngineDsp(
                    pipeline.decoder.sampleRate,
                    CHANNELS,
                    deviceRate,
                    item.episodeId,
                    timeline,
                    { pcm -> writtenFrames += write(request, out, pcm) },
                    initial.speed,
                    initial.skipSilence,
                    { settings.get().let { it.volume * it.fade } },
                    { frame, fromUs, toUs ->
                        synchronized(control) {
                            if (isCurrent(request)) {
                                silenceJumps.addLast(
                                    SilenceJump(
                                        frame,
                                        EngineEvent.Discontinuity(
                                            item.episodeId,
                                            DiscontinuityReason.SILENCE_SKIP,
                                            fromUs / MICROS_PER_MS,
                                            toUs / MICROS_PER_MS,
                                        ),
                                    ),
                                )
                            }
                        }
                    },
                )
            try {
                if (first != null) dsp.accept(first)
                if (request.positionMs == 0L || first != null) {
                    while (isCurrent(request)) {
                        val frame = pipeline.readFrame() ?: break
                        val effective = settings.get()
                        dsp.configure(effective.speed, effective.skipSilence)
                        dsp.accept(frame)
                    }
                }
                if (!isCurrent(request)) return
                dsp.finish()
                var progress = out.framesPlayed
                var lastProgress = System.nanoTime()
                while (isCurrent(request) && readFrames() < writtenFrames) {
                    val played = out.framesPlayed
                    synchronized(control) {
                        if (!wantPlaying || played != progress) lastProgress = System.nanoTime()
                    }
                    progress = played
                    check(System.nanoTime() - lastProgress < DRAIN_WAIT_NS) { "output stopped consuming PCM" }
                    Thread.sleep(POLL_MS)
                }
                if (isCurrent(request)) {
                    synchronized(control) {
                        if (!isCurrent(request)) return
                        out.stop()
                        publishSilenceJumps(out)
                        val endUs =
                            if (first == null && request.positionMs > 0) {
                                request.positionMs * MICROS_PER_MS
                            } else {
                                dsp.endUs
                            }
                        timeline.finish(endUs)
                        mutableState.value = EngineState.Ended
                    }
                }
            } finally {
                synchronized(control) {
                    output = null
                    outputReady = false
                    out.stop()
                }
            }
        }
    }

    private fun openOutput(): NdOutput =
        NdOutput.open(ndmediaDirectory, backend.nativeName, deviceRate, CHANNELS, RING_MS).also { ownedOutput = it }

    private fun write(
        request: Request,
        out: NdOutput,
        pcm: ShortArray,
    ): Long {
        val frames = pcm.size / CHANNELS
        Arena.ofConfined().use { arena ->
            val segment = arena.allocate(pcm.size.toLong() * Float.SIZE_BYTES)
            pcm.forEachIndexed { index, sample ->
                segment.set(ValueLayout.JAVA_FLOAT, index.toLong() * Float.SIZE_BYTES, sample / 32768f)
            }
            var written = 0
            while (written < frames && isCurrent(request)) {
                val remaining = segment.asSlice(written.toLong() * CHANNELS * Float.SIZE_BYTES)
                val accepted = out.write(remaining, frames - written)
                written += accepted
                synchronized(control) {
                    if (isCurrent(request) && accepted > 0) {
                        outputReady = true
                        queuedFrames += accepted
                        emptySinceNs = null
                        mutableState.value = EngineState.Ready(request.item.episodeId, wantPlaying)
                        applyDesiredState()
                    }
                }
                if (accepted == 0) Thread.sleep(POLL_MS)
            }
            return written.toLong()
        }
    }

    /** A blocked AVIO downcall cannot publish underrun state from nd-engine. */
    private fun monitorBuffer() =
        synchronized(control) {
            val out = output ?: return@synchronized
            publishSilenceJumps(out)
            if (closed.get() || !wantPlaying || !outputReady || mutableState.value == EngineState.Ended) {
                emptySinceNs = null
                return@synchronized
            }
            if (out.framesPlayed < queuedFrames) {
                emptySinceNs = null
                return@synchronized
            }
            val now = System.nanoTime()
            val since = emptySinceNs ?: now.also { emptySinceNs = it }
            if (now - since >= BUFFER_EMPTY_NS) {
                current?.let { mutableState.value = EngineState.Buffering(it.episodeId) }
            }
        }

    private fun publishSilenceJumps(out: NdOutput) {
        val heardFrame = out.framesPlayed - sessionBaseFrames - sessionPeriodFrames
        while (silenceJumps.firstOrNull()?.let { it.outFrame <= heardFrame } == true) {
            emit(silenceJumps.removeFirst().event)
        }
    }

    private fun emit(event: EngineEvent) {
        check(mutableEvents.tryEmit(event)) { "engine event consumer fell behind" }
    }

    private fun checkOpen() = check(!closed.get()) { "engine is closed" }

    private fun isCurrent(request: Request): Boolean = !closed.get() && request.generation == generation.get()

    private fun shouldResolveAgain(failure: Throwable): Boolean =
        failure is ContentChangedException || (failure is HttpSourceException && failure.status in RESOLVE_STATUSES)

    private fun mapError(failure: Throwable): EngineError =
        when (failure) {
            is HttpSourceException -> {
                if (failure.status == 401) EngineError.AuthRequired else EngineError.Http(failure.status)
            }

            is NoSuchFileException -> {
                EngineError.LocalFileMissing
            }

            is IOException -> {
                EngineError.Network
            }

            is NdOutputException -> {
                EngineError.Output(failure.message ?: "output failed")
            }

            is IllegalArgumentException -> {
                EngineError.UnsupportedFormat
            }

            else -> {
                EngineError.Engine(failure.message ?: failure.javaClass.simpleName)
            }
        }

    private companion object {
        const val DEFAULT_RATE = 44_100
        const val CHANNELS = 2
        const val RING_MS = 200
        const val BUFFER_POLL_MS = 50L
        const val BUFFER_EMPTY_NS = 300_000_000L
        const val POLL_MS = 2L
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3f
        const val EVENT_CAPACITY = 64
        const val MICROS_PER_MS = 1_000L
        const val CLOSE_WAIT_MS = 5_000L
        const val SOURCE_WAIT_MS = 30_000L
        const val DRAIN_WAIT_NS = 3_000_000_000L
        val RESOLVE_STATUSES = setOf(401, 403, 404, 410)
    }
}
