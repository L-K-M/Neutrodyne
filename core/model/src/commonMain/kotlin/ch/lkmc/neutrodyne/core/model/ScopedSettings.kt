// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys

/** Attribution for scoped fields; groups carry their stable identity and current display name. */
sealed interface SettingSource {
    data object PodcastOverride : SettingSource

    data class Group(
        val uuid: String,
        val name: String,
    ) : SettingSource

    data class Groups(
        val groups: List<Group>,
    ) : SettingSource

    data object NotSupported : SettingSource

    data object AppDefault : SettingSource
}

data class Effective<out T>(
    val value: T,
    val source: SettingSource,
)

/** M2/M4 editable fields. Null inherits; refresh 0 explicitly means Manual only. */
data class SettingOverrides(
    val refreshIntervalMinutes: Int? = null,
    val notifyNewEpisodes: Boolean? = null,
    val playbackSpeed: Float? = null,
    val skipSilence: Boolean? = null,
    val autoDownload: Boolean? = null,
    val autoDownloadKeepLatest: Int? = null,
    val autoDownloadNetwork: NetworkPolicy? = null,
    val autoDownloadRequireCharging: Boolean? = null,
    val includeVideoInAutoDownload: Boolean? = null,
    val deleteAfterPlayed: DeleteAfter? = null,
) {
    fun hasSupportedRefreshInterval(): Boolean =
        refreshIntervalMinutes == null || refreshIntervalMinutes in FeedsSettingKeys.REFRESH_INTERVAL_CHOICES
}

enum class SettingField {
    REFRESH_INTERVAL_MINUTES,
    NOTIFY_NEW_EPISODES,
    PLAYBACK_SPEED,
    SKIP_SILENCE,
    AUTO_DOWNLOAD,
    AUTO_DOWNLOAD_KEEP_LATEST,
    AUTO_DOWNLOAD_NETWORK,
    AUTO_DOWNLOAD_REQUIRE_CHARGING,
    INCLUDE_VIDEO_IN_AUTO_DOWNLOAD,
    DELETE_AFTER_PLAYED,
}

/** Group dependents cannot remain latent while the group's policy is off or inherited. */
fun SettingOverrides.withGroupAutoDownloadGating(): SettingOverrides =
    if (autoDownload == true) {
        this
    } else {
        copy(
            autoDownloadKeepLatest = null,
            autoDownloadNetwork = null,
            autoDownloadRequireCharging = null,
            includeVideoInAutoDownload = null,
        )
    }

fun SettingOverrides.hasSupportedKeepLatest(): Boolean =
    autoDownloadKeepLatest == null ||
        autoDownloadKeepLatest in ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys.KEEP_LATEST_RANGE

/** One player edit; other playback and non-playback fields must survive chain clearing. */
sealed interface PlaybackSettingChange {
    data class Speed(
        val value: Float,
    ) : PlaybackSettingChange

    data class SkipSilence(
        val value: Boolean,
    ) : PlaybackSettingChange
}

enum class PlaybackScopeWrite { APPLIED, NO_CONTEXT_GROUP, MISSING_PODCAST }

/** Member groups are hints on a podcast screen, never its no-context playback fallback. */
data class GroupPlaybackHint(
    val groupId: Long,
    val name: String,
    val speed: Float?,
    val skipSilence: Boolean?,
)

fun SettingOverrides.hasSupportedPlaybackSpeed(): Boolean =
    playbackSpeed == null || PlaybackSettingKeys.isValidSpeed(playbackSpeed)

/** Effective refresh null means Manual only, whereas own refresh null means inherit. */
data class ScopedSettingsView(
    val own: SettingOverrides,
    val effective: SettingOverrides,
    val sources: Map<SettingField, SettingSource>,
    val groupHints: List<GroupPlaybackHint> = emptyList(),
)
