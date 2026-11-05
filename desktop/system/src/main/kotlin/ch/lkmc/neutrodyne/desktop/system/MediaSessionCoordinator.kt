// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.model.settings.HardwareButtons
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.playback.api.PlaybackStateSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.roundToLong

/**
 * Wires the OS media session to the shared playback state and controller (11 Contracts:
 * "`DesktopPlaybackController` publishes … and consumes `commands`"). Every [PlaybackStateSource.nowPlaying]
 * change — the controller already republishes on state, rate, seek and transition changes
 * and on its 5-s playing tick — is pushed into the session together with the skip increments
 * and speed presets the OS surfaces label their buttons with. Commands dispatch to the shared
 * controller on this coordinator's scope, never on the thread the OS delivered them from;
 * next/previous follow `playback.hardware_buttons` read live (06 SessionPlayer).
 *
 * Seeks are rate-limited to [SEEKS_PER_SECOND] per second (11 Remote commands) — a dropped
 * intermediate seek is the position the OS UI already moved past. `SetRate` from an external
 * controller would apply for the current item only; until the controller grows that seam the
 * command is logged and dropped (11's dated note), never silently persisted.
 */
class MediaSessionCoordinator(
    private val scope: CoroutineScope,
    private val state: PlaybackStateSource,
    private val controller: PlaybackController,
    private val session: SystemMediaSession,
    private val skipBackMs: Flow<Long>,
    private val skipForwardMs: Flow<Long>,
    private val speedPresets: Flow<List<Float>>,
    private val hardwareButtons: StateFlow<HardwareButtons>,
    private val setExternalRate: (suspend (Float) -> Unit)? = null,
    private val nanos: () -> Long = System::nanoTime,
) {
    fun start() {
        combine(
            state.nowPlaying,
            skipBackMs,
            skipForwardMs,
            speedPresets,
        ) { nowPlaying, back, forward, presets ->
            session.publish(nowPlaying, back, forward, presets)
        }.launchIn(scope)

        session.commands
            .onEach(::dispatch)
            .launchIn(scope)
    }

    private suspend fun dispatch(command: RemoteCommand) {
        when (command) {
            RemoteCommand.Play -> controller.play()

            RemoteCommand.Pause -> controller.pause()

            RemoteCommand.Toggle -> controller.togglePlayPause()

            RemoteCommand.Next ->
                if (hardwareButtons.value == HardwareButtons.EPISODE) {
                    controller.skipToNext()
                } else {
                    controller.skipForward()
                }

            RemoteCommand.Previous ->
                if (hardwareButtons.value == HardwareButtons.EPISODE) {
                    controller.skipToPrevious()
                } else {
                    controller.skipBack()
                }

            RemoteCommand.SkipForward -> controller.skipForward()

            RemoteCommand.SkipBack -> controller.skipBack()

            is RemoteCommand.SeekTo -> {
                if (seekAllowed()) controller.seekTo(command.positionMs)
            }

            is RemoteCommand.SetRate -> applyExternalRate(command.rate)
        }
    }

    @Volatile private var lastSeekNanos: Long = Long.MIN_VALUE

    private fun seekAllowed(): Boolean {
        val now = nanos()
        if (lastSeekNanos != Long.MIN_VALUE && now - lastSeekNanos < seekWindowNanos()) return false
        lastSeekNanos = now
        return true
    }

    private fun seekWindowNanos(): Long = (1_000_000_000L.toDouble() / SEEKS_PER_SECOND).roundToLong()

    private val rateDropped = java.util.concurrent.atomic.AtomicBoolean(false)

    private suspend fun applyExternalRate(rate: Float) {
        if (!rate.isFinite()) return
        val sink = setExternalRate
        if (sink == null) {
            if (rateDropped.compareAndSet(false, true)) {
                Log.w(TAG) {
                    "external rate changes are pending the controller's external-speed support; dropped $rate"
                }
            }
            return
        }
        sink(PlaybackSettingKeys.normalizeSpeed(rate))
    }

    private companion object {
        const val TAG = "MediaSession"

        /** 11 Remote commands: seeks are rate-limited to 10 per second. */
        const val SEEKS_PER_SECOND = 10.0
    }
}
