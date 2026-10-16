// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/** Attribution for M2 fields; groups carry their stable identity and current display name. */
sealed interface SettingSource {
    data object PodcastOverride : SettingSource

    data class Group(
        val uuid: String,
        val name: String,
    ) : SettingSource

    data class Groups(
        val groups: List<Group>,
    ) : SettingSource

    data object AppDefault : SettingSource
}

data class Effective<out T>(
    val value: T,
    val source: SettingSource,
)

/** M2's editable subset. Null inherits; refresh 0 explicitly means Manual only. */
data class SettingOverrides(
    val refreshIntervalMinutes: Int? = null,
    val notifyNewEpisodes: Boolean? = null,
) {
    fun hasSupportedRefreshInterval(): Boolean =
        refreshIntervalMinutes == null || refreshIntervalMinutes in REFRESH_CHOICES

    private companion object {
        val REFRESH_CHOICES = setOf(0, 60, 120, 240, 480, 720, 1440)
    }
}

enum class SettingField { REFRESH_INTERVAL_MINUTES, NOTIFY_NEW_EPISODES }

/** Effective refresh null means Manual only, whereas own refresh null means inherit. */
data class ScopedSettingsView(
    val own: SettingOverrides,
    val effective: SettingOverrides,
    val sources: Map<SettingField, SettingSource>,
)
