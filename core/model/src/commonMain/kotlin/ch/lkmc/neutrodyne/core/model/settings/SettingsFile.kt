// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

/**
 * Which DataStore file a setting lives in (01 DataStore files and typed setting keys):
 * `PORTABLE -> "settings"`, `DEVICE -> "device_settings"`. [storeName] is the DataStore file
 * base name — the store factory writes `<storeName>.preferences_pb`.
 */
enum class SettingsFile(
    val storeName: String,
) {
    /** `settings` — the portable store; what backup and sync carry. */
    PORTABLE("settings"),

    /** `device_settings` — stays on this device; `ui.*`, `desktop.*`, `sync.device_*` etc. */
    DEVICE("device_settings"),
}
