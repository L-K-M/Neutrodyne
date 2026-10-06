// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

/**
 * The registry `exportSyncSet` and the sync diff walk to know which keys are portable (01 DataStore
 * files and typed setting keys). Concatenates every area's `ALL` list — populated when the areas
 * land (01 Test rigging: "for M0a the list is empty").
 *
 * Registry rules enforced by the unit test in this module:
 * - every name matches `^(area)\.[a-z0-9_]+$` with an area from [KEY_AREAS];
 * - names are unique;
 * - `ui.*` and `desktop.*` keys live in [SettingsFile.DEVICE];
 * - `synced == true` only on [SettingsFile.PORTABLE] keys whose prefix is not in [NO_SYNC_PREFIXES];
 * - `sync.server_url` is [SettingsFile.PORTABLE] and never `synced`.
 */
object AllSettingKeys {
    val list: List<SettingKey<*>> = emptyList()
}

/** First segment of a key name — one per settings area plus the local/sync prefixes (01). */
internal val KEY_AREAS: Set<String> =
    setOf(
        "appearance",
        "feeds",
        "discover",
        "groups",
        "playback",
        "downloads",
        "youtube",
        "updates",
        "backup",
        "privacy",
        "diagnostics",
        "ui",
        "sync",
        "desktop",
    )

/** Prefixes that must live in the device-local store (01). */
internal val DEVICE_ONLY_PREFIXES: Set<String> = setOf("ui.", "desktop.")

/** Prefixes that may never set `synced` (01: ui/desktop device-local; the rest excluded). */
internal val NO_SYNC_PREFIXES: Set<String> =
    setOf(
        "ui.",
        "desktop.",
        "updates.",
        "downloads.",
        "sync.",
    )

internal val SETTING_KEY_NAME: Regex =
    Regex(
        "^(appearance|feeds|discover|groups|playback|downloads|youtube|updates|backup|privacy|diagnostics|ui|sync|desktop)\\.[a-z0-9_]+$",
    )

/** Returns the list of registry-rule violations for [key]; empty means the key is well-formed. */
internal fun validateSettingKey(key: SettingKey<*>): List<String> {
    val problems = mutableListOf<String>()

    if (!key.name.matches(SETTING_KEY_NAME)) {
        problems += "'${key.name}' does not match ${SETTING_KEY_NAME.pattern}"
    }

    if (DEVICE_ONLY_PREFIXES.any(key.name::startsWith) && key.file != SettingsFile.DEVICE) {
        problems += "'${key.name}' is device-local but files under ${key.file}"
    }

    if (key.synced) {
        if (key.file != SettingsFile.PORTABLE) {
            problems += "'${key.name}' is synced but not portable"
        }
        if (NO_SYNC_PREFIXES.any(key.name::startsWith)) {
            problems += "'${key.name}' is in a never-synced prefix"
        }
    }

    if (key.name == "sync.server_url" &&
        (key.file != SettingsFile.PORTABLE || key.synced)
    ) {
        problems += "'sync.server_url' must be portable and not synced"
    }

    return problems
}
