// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.DesktopSettingKeys
import ch.lkmc.neutrodyne.playback.desktop.DesktopPlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The in-app volume behind the Playback menu's Volume up/down and Ctrl/Cmd+↑/↓ (11 Menus,
 * 08 Keyboard and mouse). The value lives in the device-local `desktop.volume` key; the
 * controller applies it to the engine and restores it on the next start.
 */
internal class DesktopUserVolume(
    private val controller: DesktopPlaybackController,
    private val settings: SettingsRepository?,
    private val scope: CoroutineScope,
) {
    var volume: Float = controller.getUserVolume()
        private set

    /** Restores the stored volume; called once the settings repository can be read. */
    suspend fun restore() {
        val stored = settings?.get(DesktopSettingKeys.VOLUME) ?: DesktopSettingKeys.VOLUME.default
        volume = stored
        controller.setUserVolume(stored)
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
        controller.setUserVolume(next)
        scope.launch {
            val result =
                runCatching { settings?.set(DesktopSettingKeys.VOLUME, next) }
                    .onFailure { Log.w("DesktopUserVolume", it) { "volume write failed" } }
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
