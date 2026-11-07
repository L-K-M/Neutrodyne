// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.DesktopSettingKeys
import ch.lkmc.neutrodyne.playback.desktop.DesktopPlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * The in-app volume behind the Playback menu's Volume up/down and Ctrl/Cmd+↑/↓ (11 Menus,
 * 08 Keyboard and mouse). The value lives in the device-local `desktop.volume` key; the
 * controller applies it to the engine and restores it on the next start.
 */
internal class DesktopUserVolume(
    currentVolume: () -> Float,
    private val applyVolume: (Float) -> Unit,
    private val settings: SettingsRepository?,
    private val scope: CoroutineScope,
) {
    constructor(
        controller: DesktopPlaybackController,
        settings: SettingsRepository?,
        scope: CoroutineScope,
    ) : this(controller::getUserVolume, controller::setUserVolume, settings, scope)

    @Volatile
    var volume: Float = currentVolume()
        private set

    private val writes = Mutex()

    /** Restores the stored volume; called once the settings repository can be read. */
    suspend fun restore() {
        // The key has no range validator, so a hand-edited or corrupt value is clamped here; a
        // failed read keeps the default rather than failing the window's start-up effect.
        val stored =
            try {
                settings?.get(DesktopSettingKeys.VOLUME) ?: DesktopSettingKeys.VOLUME.default
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("DesktopUserVolume", failure) { "volume read failed" }
                DesktopSettingKeys.VOLUME.default
            }
        val clamped = stored.coerceIn(0f, 1f)
        volume = clamped
        applyVolume(clamped)
    }

    fun volumeUp() {
        adjust(VOLUME_STEP)
    }

    fun volumeDown() {
        adjust(-VOLUME_STEP)
    }

    private fun adjust(delta: Float) {
        val next = (volume + delta).coerceIn(0f, 1f)
        if (next == volume) return
        volume = next
        applyVolume(next)
        scope.launch {
            // One write at a time, each storing the volume current when it runs: a slow earlier
            // write can no longer land after a later one and leave an older value stored.
            val result =
                writes
                    .withLock {
                        runCatching { settings?.set(DesktopSettingKeys.VOLUME, volume) }
                    }.onFailure { Log.w("DesktopUserVolume", it) { "volume write failed" } }
                    .getOrNull()
            if (result is Outcome.Failure) {
                Log.w("DesktopUserVolume") { "volume write rejected: ${result.error}" }
            }
        }
    }

    private companion object {
        const val VOLUME_STEP = 0.1f
    }
}
