// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

/** M8 portable preferences are backed up, never synced; device health state never leaves device. */
object YouTubeSettingKeys {
    val SUGGEST_RSS = SettingKey.Bool("youtube.suggest_rss", true)
    val MARK_PLAYED_ON_OPEN = SettingKey.Bool("youtube.mark_played_on_open", true)

    // Typed keys cannot hold null: zero encodes the absent epoch deadline.
    val FEED_OUTAGE_UNTIL = SettingKey.Int64("youtube.feed_outage_until", 0, SettingsFile.DEVICE)
    val FEED_OUTAGE_LEVEL = SettingKey.Int32("youtube.feed_outage_level", 0, SettingsFile.DEVICE)
    val FEED_RATE_LIMITED_UNTIL = SettingKey.Int64("youtube.feed_rate_limited_until", 0, SettingsFile.DEVICE)
    val FEED_RATE_LIMIT_LEVEL = SettingKey.Int32("youtube.feed_rate_limit_level", 0, SettingsFile.DEVICE)
    val ALL: List<SettingKey<*>> =
        listOf(
            SUGGEST_RSS,
            MARK_PLAYED_ON_OPEN,
            FEED_OUTAGE_UNTIL,
            FEED_OUTAGE_LEVEL,
            FEED_RATE_LIMITED_UNTIL,
            FEED_RATE_LIMIT_LEVEL,
        )
}

enum class YouTubeRowArt { VIDEO_THUMBNAIL, CHANNEL_AVATAR }
