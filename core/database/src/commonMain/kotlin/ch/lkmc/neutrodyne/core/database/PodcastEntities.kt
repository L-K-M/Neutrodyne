// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.DeleteAfter
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.GroupKind
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.MemberSource
import ch.lkmc.neutrodyne.core.model.NetworkPolicy
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.ShowType
import ch.lkmc.neutrodyne.core.model.SourceType

/**
 * `podcast`: one row per subscription (02 podcast). Feed-derived plus fetch-state columns; the
 * shared-table write rules of 02 DAO rules apply (targeted UPDATEs only).
 */
@Entity(
    tableName = "podcast",
    indices = [
        Index("feedKey", unique = true),
        Index("syncId", unique = true),
        Index("nextRefreshAt"),
        Index("podcastGuid"),
        Index("credentialId"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = CredentialEntity::class,
            parentColumns = ["id"],
            childColumns = ["credentialId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class PodcastEntity(
    /** Sync record ID (UUIDv4, lowercase): set by every insert path (02 podcast.syncId). */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncId: String,
    val sourceType: SourceType,
    val feedUrl: String,
    val feedKey: String,
    val youtubeChannelId: String? = null,
    @ColumnInfo(defaultValue = "1") val youtubeVariants: Int = 1,
    val channelMetadataAt: Long? = null,
    val podcastGuid: String? = null,
    @ColumnInfo(defaultValue = "0") val podcastGuidDerived: Boolean = false,
    val title: String,
    val author: String? = null,
    val link: String? = null,
    val language: String? = null,
    val explicit: Boolean? = null,
    val showType: ShowType? = null,
    val medium: String? = null,
    val locked: Boolean? = null,
    @ColumnInfo(defaultValue = "0") val complete: Boolean = false,
    val artworkUrl: String? = null,
    val artworkKey: String,
    val bannerUrl: String? = null,
    val customTitle: String? = null,
    @ColumnInfo(defaultValue = "1") val includeInAll: Boolean = true,
    val episodeOrder: FeedOrder? = null,
    val autoDownloadEligibleAfter: Long? = null,
    val status: PodcastStatus,
    val initialFetch: Boolean,
    val subscribedAt: Long,
    val latestEpisodeAt: Long? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val contentSha256: String? = null,
    @ColumnInfo(defaultValue = "0") val parserVersion: Int = 0,
    @ColumnInfo(defaultValue = "0") val lastParseOk: Boolean = false,
    val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastFullFetchAt: Long? = null,
    val nextRefreshAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val failureCount: Int = 0,
    val lastErrorKind: FeedErrorKind? = null,
    val lastErrorDetail: String? = null,
    @ColumnInfo(defaultValue = "0") val gone: Boolean = false,
    @ColumnInfo(defaultValue = "0") val needsCredentials: Boolean = false,
    val ttlMinutes: Int? = null,
    val updateFrequencyRrule: String? = null,
    val pendingNewFeedUrl: String? = null,
    val pagingNextUrl: String? = null,
    @ColumnInfo(defaultValue = "0") val pagingComplete: Boolean = false,
    val hubUrl: String? = null,
    @ColumnInfo(defaultValue = "0") val usesPodping: Boolean = false,
    val credentialId: Long? = null,
    // Large columns last (02 Conventions): SQLite reads hot columns without walking overflow pages.
    val descriptionHtml: String? = null,
    val categoriesJson: String? = null,
)

/**
 * `podcast_url_alias`: every identity-normalised URL the podcast was known by (02). Natural key
 * `url`; never equal to any `podcast.feedKey`.
 */
@Entity(
    tableName = "podcast_url_alias",
    indices = [Index("podcastId")],
    foreignKeys = [
        ForeignKey(
            PodcastEntity::class,
            parentColumns = ["id"],
            childColumns = ["podcastId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PodcastUrlAliasEntity(
    @PrimaryKey val url: String,
    val podcastId: Long,
    val reason: AliasReason,
    val addedAt: Long,
)

/**
 * `credential` (02): the secret lives encrypted on Android (`secretCipher`/`iv`); on the desktop
 * the row carries no secret (`DesktopSecretStore` keeps it by origin), so the cascades and sweeps
 * work unchanged on both platforms.
 */
@Entity(tableName = "credential", indices = [Index("origin")])
data class CredentialEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val origin: String,
    val username: String,
    val secretCipher: ByteArray?,
    val iv: ByteArray?,
    val createdAt: Long,
) {
    // ByteArray fields compare by reference under generated equals, which breaks Flow
    // deduplication and diff consumers on CredentialDao.observeAll — compare by content.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CredentialEntity) return false
        return id == other.id &&
            origin == other.origin &&
            username == other.username &&
            secretCipher.contentEquals(other.secretCipher) &&
            iv.contentEquals(other.iv) &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + origin.hashCode()
        result = 31 * result + username.hashCode()
        result = 31 * result + secretCipher.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}

/**
 * `podcast_settings` / `podcast_group_settings` shared embedded override set (02 `ScopeOverrides`):
 * `null` = inherit. The first five fields sync; the rest are device-local.
 */
data class ScopeOverrides(
    val playbackSpeed: Float? = null,
    val skipSilence: Boolean? = null,
    val boostDb: Float? = null,
    val introSkipMs: Long? = null,
    val outroSkipMs: Long? = null,
    // Device-local from here on.
    val autoDownload: Boolean? = null,
    val autoDownloadKeepLatest: Int? = null,
    val autoDownloadNetwork: NetworkPolicy? = null,
    val autoDownloadRequireCharging: Boolean? = null,
    val deleteAfterPlayed: DeleteAfter? = null,
    val includeVideoInAutoDownload: Boolean? = null,
    val notifyNewEpisodes: Boolean? = null,
    val refreshIntervalMinutes: Int? = null,
)

/** `podcast_settings`: per-podcast overrides; the row is deleted when every override is null. */
@Entity(
    tableName = "podcast_settings",
    foreignKeys = [
        ForeignKey(
            PodcastEntity::class,
            parentColumns = ["id"],
            childColumns = ["podcastId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PodcastSettingsEntity(
    @PrimaryKey val podcastId: Long,
    @Embedded val o: ScopeOverrides,
)

/** `podcast_group` (02): `uuid` is the sync record ID, `orderKey` the groups-list order. */
@Entity(
    tableName = "podcast_group",
    indices = [Index("uuid", unique = true), Index("nameKey", unique = true), Index("orderKey")],
)
data class PodcastGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val name: String,
    val nameKey: String,
    val orderKey: String,
    val colorArgb: Int? = null,
    val iconKey: String? = null,
    @ColumnInfo(defaultValue = "'MANUAL'") val kind: GroupKind = GroupKind.MANUAL,
    @ColumnInfo(defaultValue = "'NEWEST_FIRST'") val feedOrder: FeedOrder = FeedOrder.NEWEST_FIRST,
    @ColumnInfo(defaultValue = "'NEWEST_FIRST'") val playOrder: FeedOrder = FeedOrder.NEWEST_FIRST,
    @ColumnInfo(defaultValue = "0") val filterFlags: Int = 0,
    @ColumnInfo(defaultValue = "'ALL'") val mediaFilter: MediaFilter = MediaFilter.ALL,
    val hideOlderThanDays: Int? = null,
    @ColumnInfo(defaultValue = "1") val showAsTab: Boolean = true,
    val lastViewedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val ruleJson: String? = null,
)

/**
 * `podcast_group_member` (02): WITHOUT ROWID on `(groupId, podcastId)`; `orderKey` orders members
 * inside one group and is supplied on every insert.
 */
@Entity(
    tableName = "podcast_group_member",
    primaryKeys = ["groupId", "podcastId"],
    withoutRowId = true,
    indices = [Index("podcastId", "groupId")],
    foreignKeys = [
        ForeignKey(
            entity = PodcastGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PodcastEntity::class,
            parentColumns = ["id"],
            childColumns = ["podcastId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PodcastGroupMemberEntity(
    val groupId: Long,
    val podcastId: Long,
    val orderKey: String,
    val addedAt: Long,
    @ColumnInfo(defaultValue = "'MANUAL'") val source: MemberSource = MemberSource.MANUAL,
)

/** `podcast_group_settings`: per-group overrides, same embedded shape as `podcast_settings`. */
@Entity(
    tableName = "podcast_group_settings",
    foreignKeys = [
        ForeignKey(
            PodcastGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PodcastGroupSettingsEntity(
    @PrimaryKey val groupId: Long,
    @Embedded val o: ScopeOverrides,
)
