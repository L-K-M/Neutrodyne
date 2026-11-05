// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.engine

import ch.lkmc.neutrodyne.core.model.EpisodeChapter
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The desktop audio engine seam (11 Engine contracts, D86). [FfAudioEngine]
 * drives decode, DSP, the ring and the device on its own thread;
 * an LGPL libmpv build can replace it behind this interface. MD0's
 * [Md0AudioProbe] is a separate single-local-item instrument, not an
 * implementation of the production queue/controller contract.
 *
 * Every method may be called from any thread and returns immediately: commands
 * are serialised on the engine thread, `positionMs` is lock-free. Callbacks and
 * state updates never block the caller.
 */
interface AudioEngine : AutoCloseable {
    /** Current phase; the controller maps it to `:playback:api`'s `PlayerPhase`. */
    val state: StateFlow<EngineState>

    /** One-shot events: transitions, discontinuities, track metadata, device changes. */
    val events: SharedFlow<EngineEvent>

    /**
     * Replace the projection window. [currentIndex] selects the item to prepare;
     * [startPositionMs] is where it starts (null = 0, or the item's own resume
     * position when the controller resolved one).
     */
    fun setWindow(
        items: List<EngineItem>,
        currentIndex: Int,
        startPositionMs: Long?,
    )

    /**
     * Apply a queue diff without re-preparing the current item. The diff is
     * validated against the current window; a diff that touches the current item
     * is rejected and the window is left untouched.
     */
    fun updateWindow(diff: EngineWindowDiff)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    /** 0.5..3.0, Sonic, pitch kept (06 Player configuration). */
    fun setSpeed(speed: Float)

    fun setSkipSilence(enabled: Boolean)

    /** Linear gain 0..1 (GainStage: user volume). */
    fun setVolume(linear: Float)

    /** Sleep-timer fade factor 0..1 (GainStage). */
    fun setFade(factor: Float)

    /** Current position in milliseconds; safe from any thread (TimelineClock). */
    fun positionMs(): Long

    /**
     * MD2's route monitor reports the output device in use disappeared (11 Device
     * loss, sleep and session restore). The engine discards its output so the next
     * play opens the default device; the controller pauses and saves. The default
     * implementation does nothing, because the null and fake backends have no
     * device to lose.
     */
    fun onDeviceRemoved() = Unit

    /** Stop the engine thread and release the pipeline; the object is then unusable. */
    override fun close()
}

/**
 * One queue entry (06 EpisodeResolver's identity). `cacheKey` is the resolution
 * key of 06: `ep:{episodeId}:{fingerprint}` for an enclosure or
 * `yt:{videoId}:{formatId}` for a YouTube stream; `localHint` says a download
 * exists, so the resolver tries the local file first.
 */
data class EngineItem(
    val episodeId: Long,
    val cacheKey: String,
    val localHint: Boolean,
)

/** Engine phases (11 Engine contracts); the diagram of 11 Engine thread and commands draws them. */
sealed interface EngineState {
    data object Idle : EngineState

    data class Buffering(
        val episodeId: Long,
    ) : EngineState

    data class Ready(
        val episodeId: Long,
        val playing: Boolean,
    ) : EngineState

    data object Ended : EngineState

    data class Error(
        val episodeId: Long,
        val error: EngineError,
    ) : EngineState
}

/** Engine events; the controller turns them into `:playback:api` events and OS-session updates. */
sealed interface EngineEvent {
    data class ItemTransition(
        val fromEpisodeId: Long?,
        /**
         * The outgoing item's audible position at the boundary, in milliseconds.
         * The engine has already handed the ring to [toEpisodeId] when this event
         * is emitted, so the clock no longer reports the outgoing item; the
         * controller captures the departure from this value, mirroring Media3's
         * `oldPosition.positionMs` at `onPositionDiscontinuity`.
         */
        val fromPositionMs: Long,
        val toEpisodeId: Long,
        val reason: TransitionReason,
    ) : EngineEvent

    data class Discontinuity(
        val episodeId: Long,
        val reason: DiscontinuityReason,
        val fromMs: Long,
        val toMs: Long,
    ) : EngineEvent

    data class TracksKnown(
        val episodeId: Long,
        val durationMs: Long?,
        val chapters: List<EpisodeChapter>,
        val gapless: Gapless,
    ) : EngineEvent

    data class DeviceRerouted(
        val deviceName: String,
    ) : EngineEvent

    data object DeviceLost : EngineEvent
}

/** Why the engine moved to another item (11 Engine contracts). */
enum class TransitionReason { AUTO, SEEK_TO_ITEM, SKIP }

/** Why the position jumped without a user-visible seek (11 Engine contracts). */
enum class DiscontinuityReason { SEEK, SILENCE_SKIP }

/**
 * Container trimming information the demuxer reports (11 Demux and decode). The
 * decoder applies FFmpeg's skip-samples side data itself; these values are what
 * `TracksKnown` publishes so the controller and the position rules see the same
 * picture.
 */
data class Gapless(
    val initialPadding: Int,
    val trailingPadding: Int,
    val seekPreroll: Int,
)

/** Mapped to `:playback:api`'s `UnplayableReason` / `PlaybackIssue` by the controller (11 Engine errors). */
sealed interface EngineError {
    data class Http(
        val status: Int,
    ) : EngineError

    data object AuthRequired : EngineError

    data object UnsupportedFormat : EngineError

    data object NoMedia : EngineError

    /**
     * The representation changed under a ranged read (DAI length or validator).
     * The engine has already re-resolved once, so this is the second failure.
     */
    data object ContentChanged : EngineError

    data object Network : EngineError

    data object LocalFileMissing : EngineError

    data class YouTube(
        val result: String,
    ) : EngineError

    data class Output(
        val message: String,
    ) : EngineError

    data class Engine(
        val message: String,
    ) : EngineError
}
