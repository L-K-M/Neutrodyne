// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceDto

/**
 * 10 Now playing and handoff: resolves a writer device id (`RecordDto.by`) to the display name
 * the "Continue on this device" card shows (08). `null` means the name is unknown — callers fall
 * back to the raw device id. Implementations may hit the network; they are never called inside a
 * Room transaction or the adopter's decision mutex.
 */
fun interface DeviceNames {
    suspend fun name(deviceId: String): String?
}

/**
 * The `SyncClient.devices()`-backed lookup. One fetch fills the process-run map; a miss on a
 * later writer id refetches once, so a rename or a freshly-seen device still resolves.
 * Transport failures yield `null` — the offer publishes with the raw id rather than waiting.
 */
class ClientDeviceNames(
    private val devices: suspend () -> List<DeviceDto>,
) : DeviceNames {
    private val cache = mutableMapOf<String, String>()
    private var fetched = false

    override suspend fun name(deviceId: String): String? {
        if (!fetched || deviceId !in cache) {
            val listed = suspendRunCatching { devices() }.getOrNull() ?: return null
            fetched = true
            listed.forEach { cache[it.id] = it.name }
        }
        return cache[deviceId]
    }
}
