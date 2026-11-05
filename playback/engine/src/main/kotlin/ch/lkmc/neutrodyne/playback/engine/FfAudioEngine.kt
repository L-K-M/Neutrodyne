// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import ch.lkmc.neutrodyne.playback.native.AudioOutput
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
import java.util.concurrent.Future
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
    private val outputFactory: () -> AudioOutput = {
        NdOutput.open(ndmediaDirectory, backend.nativeName, deviceRate, CHANNELS, RING_MS)
    },
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

    // Unbounded: a collector that lags (a slow transition write) must never fail the engine
    // thread's emit. Events are small and rare, so the backlog stays bounded in practice.
    private val mutableEvents = MutableSharedFlow<EngineEvent>(extraBufferCapacity = Int.MAX_VALUE)
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

    /** The next item's in-flight preparation, its source and its job. */
    @Volatile private var pendingItem: EngineItem? = null
    private var pendingSource: ByteSource? = null
    private var pendingJob: Job? = null

    /**
     * The ring frame at which the arriving item's first marker lands, and the
     * transition to emit once the device has consumed it. Both are only touched
     * under [control].
     */
    private var transitionAtFrame: Long? = null
    private var transitionEvent: EngineEvent.ItemTransition? = null
    private var output: AudioOutput? = null
    private var ownedOutput: AudioOutput? = null

    /**
     * The output failure being reported, or null. Set once: the failed device is
     * discarded so the next play opens a fresh one, and the generation bump stops
     * the running chain without re-resolving it.
     */
    @Volatile private var outputFailure: NdOutputException? = null
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

    /** Test-visible underrun counter: frames the device wanted while the ring was empty. */
    @Volatile internal var outputUnderrunFrames: (() -> Long)? = null
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
            // A removed successor invalidates its in-flight preparation; aborting
            // its source lets a blocked read return instead of stalling the
            // prepare thread behind a stale item.
            val pending = pendingItem
            if (pending != null && pending.episodeId in diff.removed) {
                pendingSource?.abort()
                pendingJob?.cancel()
            }
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

    /**
     * MD2's route monitor reports the output device in use disappeared (11 Device
     * loss). The engine discards its output so the next play re-opens the default
     * device; the controller pauses and saves.
     */
    override fun onDeviceRemoved() {
        synchronized(control) {
            if (closed.get()) return
            if (output != null && outputFailure == null) {
                failOutput(NdOutputException("the output device was removed", NdOutputException.E_DEVICE))
            }
        }
    }

    override fun close() {
        synchronized(control) {
            if (!closed.compareAndSet(false, true)) return
            generation.incrementAndGet()
            source?.abort()
            pendingSource?.abort()
            resolveJob?.cancel()
            pendingJob?.cancel()
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
        pendingSource?.abort()
        resolveJob?.cancel()
        pendingJob?.cancel()
        pendingSource = null
        pendingJob = null
        pendingItem = null
        transitionAtFrame = null
        transitionEvent = null
        output?.stop()
        outputFailure = null
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
        try {
            if (wantPlaying && outputReady) {
                clock?.resume()
                out.start()
            } else {
                out.stop()
                clock?.pause()
            }
        } catch (failure: NdOutputException) {
            failOutput(failure)
            return
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
                    val future = prepare.submit<Prepared> { prepare(request, pending = false) }
                    // Even a superseded result is collected and closed. Cancellation
                    // interrupts its source/job, never an FFM call or its arena.
                    val prepared = future.get()
                    if (isCurrent(request)) {
                        playChain(request, prepared)
                    } else {
                        prepared.close()
                    }
                } catch (failure: Throwable) {
                    val problem = (failure as? java.util.concurrent.ExecutionException)?.cause ?: failure
                    synchronized(control) {
                        if (!isCurrent(request)) return@synchronized
                        // An output failure has already published its event and error.
                        if (outputFailure != null) return@synchronized
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

    /**
     * Plays one prepared item and every prepared successor into the same ring.
     * The clock and the output live for the whole chain, so a handover never
     * stops the device; the transition event fires when the arriving item's
     * first written frame has left the device (11 Transitions). The outgoing
     * padding and the incoming delay are already trimmed by the demuxer and
     * decoder (11 Demux and decode, Gapless trimming), so the handover only has
     * to keep the ring continuous.
     */
    private fun playChain(
        request: Request,
        first: Prepared,
    ) {
        val generation = request.generation
        val out =
            try {
                ownedOutput ?: openOutput()
            } catch (failure: NdOutputException) {
                failOutput(failure)
                return
            }
        // A new chain (a start or a seek) drops the previous item's queued audio;
        // a handover inside the chain never clears the ring.
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
                request.item.episodeId.toString(),
                request.positionMs * MICROS_PER_MS,
                settings.get().speed,
            ),
        )
        synchronized(control) {
            if (!isGenerationCurrent(generation)) return
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
            outputUnderrunFrames = out.underrunReader()
            source = first.source
        }
        var item = request.item
        var positionMs = request.positionMs
        var prepared = first
        var pending: Future<Prepared>? = null
        var writtenFrames = 0L
        try {
            while (isGenerationCurrent(generation)) {
                val pipeline = prepared.pipeline
                val demuxer = pipeline.demuxer
                val firstFrame = if (positionMs > 0) pipeline.seekFrame(positionMs) else null
                if (!isGenerationCurrent(generation)) return
                emit(EngineEvent.TracksKnown(item.episodeId, demuxer.durationMs, demuxer.chapters, demuxer.gapless))
                val dsp =
                    EngineDsp(
                        pipeline.decoder.sampleRate,
                        CHANNELS,
                        deviceRate,
                        item.episodeId,
                        writtenFrames,
                        timeline,
                        { pcm -> writtenFrames += write(item, generation, out, readFrames, pcm) },
                        settings.get().speed,
                        settings.get().skipSilence,
                        { settings.get().let { it.volume * it.fade } },
                        { frame, fromUs, toUs ->
                            synchronized(control) {
                                if (isGenerationCurrent(generation)) {
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
                synchronized(control) {
                    if (!isGenerationCurrent(generation)) return
                    current = item
                    source = prepared.source
                    if (!wantPlaying) timeline.pause()
                }
                try {
                    if (firstFrame != null) dsp.accept(firstFrame)
                    if (positionMs == 0L || firstFrame != null) {
                        while (isGenerationCurrent(generation)) {
                            if (pending == null && shouldPrepareAhead(demuxer, dsp)) {
                                val candidate = nextItemAfter(item)
                                if (candidate != null) {
                                    pendingItem = candidate
                                    pending =
                                        prepare.submit<Prepared> {
                                            prepare(Request(generation, candidate, 0), pending = true)
                                        }
                                }
                            }
                            val frame = pipeline.readFrame() ?: break
                            val effective = settings.get()
                            dsp.configure(effective.speed, effective.skipSilence)
                            dsp.accept(frame)
                        }
                    }
                    if (!isGenerationCurrent(generation)) return
                    dsp.finish()
                } finally {
                    synchronized(control) {
                        if (source === prepared.source) source = null
                    }
                }
                // The window may have been edited while this item played; the
                // successor is re-read rather than trusted from the prepare trigger.
                val successor = nextItemAfter(item)
                if (successor == null) {
                    val endUs = if (dsp.endUs > 0) dsp.endUs else positionMs * MICROS_PER_MS
                    finishChain(generation, out, timeline, readFrames, writtenFrames, endUs)
                    return
                }
                val nextPrepared =
                    if (pending != null && pendingItem == successor) {
                        awaitPending(generation, pending, successor)
                    } else {
                        dropPending(pending)
                        pending = null
                        pendingItem = null
                        awaitPending(generation, null, successor)
                    } ?: return
                pending = null
                pendingItem = null
                if (!isGenerationCurrent(generation)) {
                    nextPrepared.close()
                    return
                }
                val outgoing = item
                val outgoingEndMs = dsp.endUs / MICROS_PER_MS
                prepared.close()
                item = successor
                positionMs = 0
                prepared = nextPrepared
                synchronized(control) {
                    if (!isGenerationCurrent(generation)) return
                    current = item
                    source = nextPrepared.source
                    // The arrival's first marker lands at the current ring position;
                    // its transition becomes audible when the device reaches it.
                    transitionAtFrame = writtenFrames
                    transitionEvent =
                        EngineEvent.ItemTransition(
                            outgoing.episodeId,
                            outgoingEndMs,
                            successor.episodeId,
                            TransitionReason.AUTO,
                        )
                }
            }
        } finally {
            synchronized(control) {
                output = null
                outputReady = false
                // A failed output was already discarded by failOutput.
                if (outputFailure == null) out.stop()
                if (source === prepared.source) source = null
            }
            prepared.close()
        }
    }

    /** Drains the ring and publishes [EngineState.Ended] at the last item's end. */
    private fun finishChain(
        generation: Long,
        out: AudioOutput,
        timeline: TimelineClock,
        readFrames: () -> Long,
        writtenFrames: Long,
        endUs: Long,
    ) {
        var progress = out.framesPlayed
        var lastProgress = System.nanoTime()
        while (isGenerationCurrent(generation) && readFrames() < writtenFrames) {
            val played = out.framesPlayed
            synchronized(control) {
                if (!wantPlaying || played != progress) lastProgress = System.nanoTime()
            }
            progress = played
            check(System.nanoTime() - lastProgress < DRAIN_WAIT_NS) { "output stopped consuming PCM" }
            Thread.sleep(POLL_MS)
        }
        if (isGenerationCurrent(generation)) {
            synchronized(control) {
                if (!isGenerationCurrent(generation)) return
                out.stop()
                publishSilenceJumps(out)
                timeline.finish(endUs)
                mutableState.value = EngineState.Ended
            }
        }
    }

    /**
     * The window item after [item], or null at the end of the window. The
     * outgoing item stays in the window until the controller's diff removes it,
     * so the engine's list only changes through [updateWindow].
     */
    private fun nextItemAfter(item: EngineItem): EngineItem? =
        synchronized(control) {
            val index = window.indexOfFirst { it.episodeId == item.episodeId }
            if (index < 0) null else window.getOrNull(index + 1)
        }

    /** The 10-s preparation window of 11 Transitions; unknown durations prepare at EOF. */
    private fun shouldPrepareAhead(
        demuxer: FfDemuxer,
        dsp: EngineDsp,
    ): Boolean {
        val durationMs = demuxer.durationMs ?: return false
        return durationMs - dsp.endUs / MICROS_PER_MS <= PREPARE_AHEAD_MS
    }

    /** Closes a preparation the window no longer needs, without blocking the engine thread. */
    private fun dropPending(pending: Future<Prepared>?) {
        pending ?: return
        try {
            prepare.execute {
                try {
                    pending.get().close()
                } catch (_: Throwable) {
                    // The preparation cleaned up after itself.
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // The engine is closing; the generation check cleans up.
        }
    }

    /**
     * Waits for the next item's preparation, submitting it at the boundary when
     * the duration was unknown. A failure or a superseded generation stops the
     * chain; error recovery and skipping are MD1b's later work.
     */
    private fun awaitPending(
        generation: Long,
        pending: Future<Prepared>?,
        item: EngineItem,
    ): Prepared? {
        val future =
            pending
                ?: prepare.submit<Prepared> {
                    prepare(Request(generation, item, 0), pending = true)
                }
        return try {
            future.get()
        } catch (failure: Throwable) {
            val problem = (failure as? java.util.concurrent.ExecutionException)?.cause ?: failure
            synchronized(control) {
                if (isGenerationCurrent(generation)) {
                    mutableState.value = EngineState.Error(item.episodeId, mapError(problem))
                }
            }
            null
        }
    }

    private fun prepare(
        request: Request,
        pending: Boolean,
    ): Prepared =
        runBlocking {
            synchronized(control) {
                check(isCurrent(request)) { "superseded engine preparation" }
                if (pending) pendingJob = coroutineContext.job else resolveJob = coroutineContext.job
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
                    if (pending) pendingSource = input else source = input
                }
                val pipeline = FfmpegPipeline.open(input, ffmpegDirectory)
                try {
                    if (input is HttpByteSource) pipeline.demuxer.durationMs?.let(input::setDurationEstimate)
                    synchronized(control) {
                        coroutineContext.ensureActive()
                        check(isCurrent(request)) { "superseded prepared pipeline" }
                        // After handoff, cancellation must abort the source rather
                        // than cancel runBlocking and discard its native result. The
                        // Prepared owns the source now; the abort handle is no longer
                        // needed.
                        if (pending) {
                            pendingJob = null
                            pendingSource = null
                        } else {
                            resolveJob = null
                        }
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

    private fun openOutput(): AudioOutput = outputFactory().also { ownedOutput = it }

    /**
     * The documented output-device reaction (11 Engine errors and recovery): the
     * failed device is closed and discarded so the next play opens the default
     * device again, the last position is kept for the controller's save, a
     * device-level failure publishes [EngineEvent.DeviceLost], and the state
     * becomes [EngineState.Error] with [EngineError.Output]. Nothing escapes the
     * engine thread and no stale output reference survives.
     */
    private fun failOutput(failure: NdOutputException) {
        synchronized(control) {
            if (outputFailure != null) return
            outputFailure = failure
            // The clock dies with the device; keep the last audible position so
            // the controller's save never falls back to the chain's anchor.
            val lastPosition = clock?.positionMs() ?: anchorMs.get()
            anchorMs.set(lastPosition)
            output = null
            outputReady = false
            clock = null
            ownedOutput?.close()
            ownedOutput = null
            if (failure.code == NdOutputException.E_DEVICE) {
                emit(EngineEvent.DeviceLost)
            }
            current?.let {
                mutableState.value =
                    EngineState.Error(it.episodeId, EngineError.Output(failure.message ?: "output failed"))
            }
            generation.incrementAndGet()
        }
    }

    private fun write(
        item: EngineItem,
        generation: Long,
        out: AudioOutput,
        readFrames: () -> Long,
        pcm: ShortArray,
    ): Long {
        val frames = pcm.size / CHANNELS
        Arena.ofConfined().use { arena ->
            val segment = arena.allocate(pcm.size.toLong() * Float.SIZE_BYTES)
            pcm.forEachIndexed { index, sample ->
                segment.set(ValueLayout.JAVA_FLOAT, index.toLong() * Float.SIZE_BYTES, sample / 32768f)
            }
            var written = 0
            while (written < frames && isGenerationCurrent(generation)) {
                val remaining = segment.asSlice(written.toLong() * CHANNELS * Float.SIZE_BYTES)
                val accepted =
                    try {
                        out.write(remaining, frames - written)
                    } catch (failure: NdOutputException) {
                        failOutput(failure)
                        return written.toLong()
                    }
                written += accepted
                synchronized(control) {
                    if (isGenerationCurrent(generation) && accepted > 0) {
                        outputReady = true
                        queuedFrames += accepted
                        emptySinceNs = null
                        mutableState.value = EngineState.Ready(item.episodeId, wantPlaying)
                        applyDesiredState()
                        publishTransition(readFrames)
                    }
                }
                if (accepted == 0) Thread.sleep(POLL_MS)
            }
            return written.toLong()
        }
    }

    /**
     * Emits the pending item transition once the device has consumed its first
     * frame, using the same latency-adjusted position as the clock so the event,
     * the position save and what is audible agree.
     */
    private fun publishTransition(readFrames: () -> Long) {
        val frame = transitionAtFrame ?: return
        if (readFrames() - sessionPeriodFrames < frame) return
        val event = transitionEvent
        transitionAtFrame = null
        transitionEvent = null
        if (event != null) emit(event)
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

    private fun publishSilenceJumps(out: AudioOutput) {
        val heardFrame = out.framesPlayed - sessionBaseFrames - sessionPeriodFrames
        while (silenceJumps.firstOrNull()?.let { it.outFrame <= heardFrame } == true) {
            emit(silenceJumps.removeFirst().event)
        }
    }

    private fun emit(event: EngineEvent) {
        check(mutableEvents.tryEmit(event)) { "unbounded engine event buffer refused an event" }
    }

    private fun checkOpen() = check(!closed.get()) { "engine is closed" }

    private fun isCurrent(request: Request): Boolean = isGenerationCurrent(request.generation)

    private fun isGenerationCurrent(generation: Long): Boolean = !closed.get() && generation == this.generation.get()

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
        const val MICROS_PER_MS = 1_000L
        const val CLOSE_WAIT_MS = 5_000L
        const val SOURCE_WAIT_MS = 30_000L
        const val PREPARE_AHEAD_MS = 10_000L
        const val DRAIN_WAIT_NS = 3_000_000_000L
        val RESOLVE_STATUSES = setOf(401, 403, 404, 410)
    }
}
