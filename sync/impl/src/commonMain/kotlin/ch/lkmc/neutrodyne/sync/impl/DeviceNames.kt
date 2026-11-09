// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

/** Resolves a sync device id to its display name for session offers and played notices (10). */
class DeviceNames(
    private val devices: suspend () -> List<ch.lkmc.neutrodyne.sync.protocol.dto.DeviceDto>,
) {
    private var cached: Map<String, String>? = null

    /** The name of [deviceId], or `null` when the device list cannot be read. */
    suspend fun name(deviceId: String?): String? {
        if (deviceId == null) return null
        cached?.get(deviceId)?.let { return it }
        val map =
            runCatching { devices().associate { it.id to it.name } }.getOrNull() ?: return null
        cached = map
        return map[deviceId]
    }
}
