// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.DesktopSettingKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Writes `desktop.window_bounds` one second after the last move or resize and on the
 * clean-shutdown path (11 Window state). The debounce keeps a drag from writing every frame;
 * [saveNow] closes the final gap at quit.
 */
internal class WindowBoundsWriter(
    private val settings: SettingsRepository?,
    scope: CoroutineScope,
) {
    private val pending = MutableStateFlow<StoredWindowBounds?>(null)

    init {
        scope.launch {
            pending
                .debounce(WRITE_DELAY)
                .filterNotNull()
                .collect { bounds -> write(bounds) }
        }
    }

    fun onBounds(bounds: StoredWindowBounds) {
        pending.value = bounds
    }

    suspend fun saveNow() {
        pending.value?.let { write(it) }
    }

    private suspend fun write(bounds: StoredWindowBounds) {
        val text = WindowBoundsPreference.encode(bounds)
        val result = runCatching { settings?.set(DesktopSettingKeys.WINDOW_BOUNDS, text) }
        result.exceptionOrNull()?.let { Log.w(TAG, it) { "window bounds write failed" } }
        if (result.getOrNull() is Outcome.Failure) {
            Log.w(TAG) { "window bounds write rejected" }
        }
    }

    private companion object {
        const val TAG = "WindowBounds"
        val WRITE_DELAY = 1_000.milliseconds
    }
}
