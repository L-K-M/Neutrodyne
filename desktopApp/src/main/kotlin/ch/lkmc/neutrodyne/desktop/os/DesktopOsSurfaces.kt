// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.os

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.PowerMonitor
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.HardwareButtons
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.desktop.system.IdleSleepInhibitor
import ch.lkmc.neutrodyne.desktop.system.MediaSessionCoordinator
import ch.lkmc.neutrodyne.desktop.system.OsPowerMonitor
import ch.lkmc.neutrodyne.desktop.system.PowerPolicy
import ch.lkmc.neutrodyne.desktop.system.SystemMediaSession
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.playback.api.PlaybackStateSource
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The OS surfaces of the desktop shell (11 DesktopAppGraph): the shared playback state and
 * controller wired to the OS media session, and the power policy with the idle-sleep
 * inhibitor, both started once at initializer band 250 and torn down by the shutdown before
 * playback flushes its last position. The skip increments, speed presets and the
 * `playback.hardware_buttons` mapping come from the settings repository exactly as the
 * shared controller reads them.
 */
@SingleIn(AppScope::class)
@Inject
class DesktopOsSurfaces(
    @ApplicationScope private val scope: CoroutineScope,
    private val controller: PlaybackController,
    private val state: PlaybackStateSource,
    private val session: SystemMediaSession,
    private val power: PowerMonitor,
    private val inhibitor: IdleSleepInhibitor,
    settings: SettingsRepository,
) {
    private val started = AtomicBoolean(false)
    private val hardwareButtons: StateFlow<HardwareButtons> =
        settings
            .observe(PlaybackSettingKeys.HARDWARE_BUTTONS)
            .stateIn(scope, SharingStarted.Eagerly, HardwareButtons.EPISODE)

    private val mediaSession =
        MediaSessionCoordinator(
            scope = scope,
            state = state,
            controller = controller,
            session = session,
            skipBackMs = settings.observe(PlaybackSettingKeys.SKIP_BACK_MS),
            skipForwardMs = settings.observe(PlaybackSettingKeys.SKIP_FORWARD_MS),
            speedPresets =
                settings
                    .observe(PlaybackSettingKeys.SPEED_PRESETS)
                    .map { PlaybackSettingKeys.parseSpeedPresets(it) ?: DEFAULT_SPEED_PRESETS },
            hardwareButtons = hardwareButtons,
        )

    private val powerPolicy = PowerPolicy(scope, power, controller, state, inhibitor)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        mediaSession.start()
        powerPolicy.start()
    }

    /**
     * The shutdown half, run before playback's own flush: release the inhibitor, close the
     * OS session and the power source. Nothing here may throw into the shutdown path.
     */
    fun close() {
        runCatching(powerPolicy::close).onFailure { Log.w(TAG, it) { "closing the power policy failed" } }
        for (surface in listOf(session, power, inhibitor)) {
            if (surface !is AutoCloseable) continue
            runCatching(surface::close)
                .onFailure { Log.w(TAG, it) { "closing ${surface::class.simpleName} failed" } }
        }
    }

    private companion object {
        const val TAG = "DesktopOs"

        // The repository's own fallback when a stored presets string cannot be parsed.
        val DEFAULT_SPEED_PRESETS =
            listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f)
    }
}
