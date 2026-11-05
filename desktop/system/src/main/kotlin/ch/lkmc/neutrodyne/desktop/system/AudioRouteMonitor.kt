// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import kotlinx.coroutines.flow.Flow

/**
 * The OS audio-route seam (11 OS integration, Audio-route monitoring). MD2
 * implements it per OS; the playback controller consumes it (MD1b's reaction:
 * a removed device pauses and saves, a default change keeps playing).
 */
interface AudioRouteMonitor {
    val events: Flow<RouteEvent>
}

sealed interface RouteEvent {
    /** The output device in use disappeared (USB, Bluetooth, jack). */
    data class DeviceRemoved(
        val deviceId: String,
    ) : RouteEvent

    /** The default output device changed; playback continues on the new default. */
    data object DefaultChanged : RouteEvent
}

/**
 * MD1b placeholder until MD2 binds the per-OS monitor: no route events, so the
 * engine's own output-error reaction is the only device-loss path.
 */
object NoOpAudioRouteMonitor : AudioRouteMonitor {
    override val events: Flow<RouteEvent> = kotlinx.coroutines.flow.emptyFlow()
}
