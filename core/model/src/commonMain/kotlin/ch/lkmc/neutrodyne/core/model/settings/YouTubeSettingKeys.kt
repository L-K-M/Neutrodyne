// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

import kotlinx.collections.immutable.toImmutableList

/** YouTube portable preferences are backed up, never synced; device health state never leaves device. */
object YouTubeSettingKeys {
    val SUGGEST_RSS = SettingKey.Bool("youtube.suggest_rss", true)
    val MARK_PLAYED_ON_OPEN = SettingKey.Bool("youtube.mark_played_on_open", true)

    val ENGINE_ENABLED = SettingKey.Bool("youtube.engine_enabled", true)
    val AUDIO_QUALITY =
        SettingKey.Choice(
            "youtube.audio_quality",
            YouTubeAudioQuality.STANDARD,
            YouTubeAudioQuality.entries.toImmutableList(),
        )
    val VOLUME_LEVELLING = SettingKey.Bool("youtube.volume_levelling", false)
    val AUTO_DOWNLOAD = SettingKey.Bool("youtube.auto_download", false)
    val AUTO_DOWNLOAD_KEEP_LATEST = SettingKey.Int32("youtube.auto_download_keep_latest", 2)
    val BREAKER_OPEN_UNTIL = SettingKey.Int64("youtube.breaker_open_until", 0, SettingsFile.DEVICE)
    val BREAKER_LAST_OPENED_AT = SettingKey.Int64("youtube.breaker_last_opened_at", 0, SettingsFile.DEVICE)
    val BREAKER_VERSION_CODE = SettingKey.Int32("youtube.breaker_version_code", 0, SettingsFile.DEVICE)
    val BREAKER_ENGINE_VERSION = SettingKey.Text("youtube.breaker_engine_version", "", SettingsFile.DEVICE)
    val RATE_LIMITED_UNTIL = SettingKey.Int64("youtube.rate_limited_until", 0, SettingsFile.DEVICE)
    val RATE_LIMIT_LEVEL = SettingKey.Int32("youtube.rate_limit_level", 0, SettingsFile.DEVICE)
    val ENGINE_START_FAILURES = SettingKey.Int32("youtube.engine_start_failures", 0, SettingsFile.DEVICE)

    fun acceptsRange(
        key: SettingKey<*>,
        value: Any,
    ): Boolean =
        when (key) {
            AUTO_DOWNLOAD_KEEP_LATEST -> {
                value is Int && value in 1..10
            }

            FEED_OUTAGE_LEVEL,
            FEED_RATE_LIMIT_LEVEL,
            BREAKER_VERSION_CODE,
            RATE_LIMIT_LEVEL,
            ENGINE_START_FAILURES,
            -> {
                value is Int && value >= 0
            }

            FEED_OUTAGE_UNTIL,
            FEED_RATE_LIMITED_UNTIL,
            BREAKER_OPEN_UNTIL,
            BREAKER_LAST_OPENED_AT,
            RATE_LIMITED_UNTIL,
            -> {
                value is Long && value >= 0
            }

            else -> {
                true
            }
        }

    // Typed keys cannot hold null: zero encodes the absent epoch deadline.
    val FEED_OUTAGE_UNTIL = SettingKey.Int64("youtube.feed_outage_until", 0, SettingsFile.DEVICE)
    val FEED_OUTAGE_LEVEL = SettingKey.Int32("youtube.feed_outage_level", 0, SettingsFile.DEVICE)
    val FEED_RATE_LIMITED_UNTIL = SettingKey.Int64("youtube.feed_rate_limited_until", 0, SettingsFile.DEVICE)
    val FEED_RATE_LIMIT_LEVEL = SettingKey.Int32("youtube.feed_rate_limit_level", 0, SettingsFile.DEVICE)
    val ALL: List<SettingKey<*>> =
        listOf(
            ENGINE_ENABLED,
            AUDIO_QUALITY,
            VOLUME_LEVELLING,
            AUTO_DOWNLOAD,
            AUTO_DOWNLOAD_KEEP_LATEST,
            BREAKER_OPEN_UNTIL,
            BREAKER_LAST_OPENED_AT,
            BREAKER_VERSION_CODE,
            BREAKER_ENGINE_VERSION,
            RATE_LIMITED_UNTIL,
            RATE_LIMIT_LEVEL,
            ENGINE_START_FAILURES,
            SUGGEST_RSS,
            MARK_PLAYED_ON_OPEN,
            FEED_OUTAGE_UNTIL,
            FEED_OUTAGE_LEVEL,
            FEED_RATE_LIMITED_UNTIL,
            FEED_RATE_LIMIT_LEVEL,
        )
}

enum class YouTubeRowArt { VIDEO_THUMBNAIL, CHANNEL_AVATAR }

/** Shared settings/playback quality, declared here to keep model independent of youtube:api. */
enum class YouTubeAudioQuality(
    val ranks: List<Int>,
) {
    STANDARD(listOf(140, 251, 250, 139, 249)),
    DATA_SAVER(listOf(250, 249, 139, 140)),
    OPUS(listOf(251, 250, 140)),
}
