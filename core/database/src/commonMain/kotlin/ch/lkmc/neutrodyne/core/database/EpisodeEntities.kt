// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.EpisodeType
import ch.lkmc.neutrodyne.core.model.OwnerType

/**
 * `episode`: feed-derived data only (02 episode, D15). No user state, positions or download
 * progress — those live in their own tables.
 */
@Entity(
    tableName = "episode",
    indices = [
        Index("podcastId", "identityKey", unique = true),
        Index("podcastId", "sortDate"),
        Index("sortDate"),
        Index("firstSeenAt"),
    ],
    foreignKeys = [
        ForeignKey(
            PodcastEntity::class,
            parentColumns = ["id"],
            childColumns = ["podcastId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val podcastId: Long,
    val identityKey: String,
    val guid: String? = null,
    val title: String,
    val pubDate: Long? = null,
    val rawPubDate: String? = null,
    val sortDate: Long,
    val feedOrder: Int,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    @ColumnInfo(defaultValue = "1") val inFeed: Boolean = true,
    @ColumnInfo(defaultValue = "0") val isNew: Boolean = false,
    val enclosureUrl: String? = null,
    val enclosureType: String? = null,
    val enclosureLength: Long? = null,
    val externalMediaId: String? = null,
    @ColumnInfo(defaultValue = "0") val isVideo: Boolean = false,
    /** Feed or enrichment hint; 06 measures the truth into `episode_state.measuredDurationMs`. */
    val durationMs: Long? = null,
    val season: Int? = null,
    val seasonName: String? = null,
    /** Decimal as plain string ("12", "12.5"). */
    val episodeNumber: String? = null,
    val episodeDisplay: String? = null,
    val episodeType: EpisodeType? = null,
    val explicit: Boolean? = null,
    val imageUrl: String? = null,
    val artworkKey: String? = null,
    val link: String? = null,
    val chaptersUrl: String? = null,
    val chaptersType: String? = null,
    /** First 8 bytes of SHA-256 over 03's normalised fields. */
    val contentHash: Long,
    @ColumnInfo(defaultValue = "'AVAILABLE'") val availability: Availability = Availability.AVAILABLE,
    @ColumnInfo(defaultValue = "0") val isShort: Boolean = false,
    /** ≤ 200 chars plain text; last (large). */
    val snippet: String? = null,
)

/** `episode_description`: show notes kept out of the hot `episode` table; `html` is codec bytes. */
@Entity(
    tableName = "episode_description",
    foreignKeys = [
        ForeignKey(
            EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EpisodeDescriptionEntity(
    @PrimaryKey val episodeId: Long,
    val html: ByteArray,
) {
    // `html` is a ByteArray: generated equals compares it by reference, which breaks Flow
    // deduplication and diff consumers — compare by content.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EpisodeDescriptionEntity) return false
        return episodeId == other.episodeId && html.contentEquals(other.html)
    }

    override fun hashCode(): Int = 31 * episodeId.hashCode() + html.contentHashCode()
}

/** `episode_transcript` (02): one row per `<podcast:transcript>` element. */
@Entity(
    tableName = "episode_transcript",
    primaryKeys = ["episodeId", "url"],
    foreignKeys = [
        ForeignKey(
            EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EpisodeTranscriptEntity(
    val episodeId: Long,
    val url: String,
    val type: String,
    val language: String? = null,
    val rel: String? = null,
)

/** `episode_alt_enclosure`: Podcasting 2.0 `alternateEnclosure` rows (02). */
@Entity(
    tableName = "episode_alt_enclosure",
    primaryKeys = ["episodeId", "ordinal"],
    foreignKeys = [
        ForeignKey(
            EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EpisodeAltEnclosureEntity(
    val episodeId: Long,
    val ordinal: Int,
    val type: String,
    val length: Long? = null,
    val bitrate: Long? = null,
    val height: Int? = null,
    val lang: String? = null,
    val title: String? = null,
    val rel: String? = null,
    val codecs: String? = null,
    @ColumnInfo(defaultValue = "0") val isDefault: Boolean = false,
    val integrityType: String? = null,
    val integrityValue: String? = null,
    val sourcesJson: String,
)

/**
 * `person` (02): polymorphic owner — `(ownerType, ownerId)` references `podcast` or `episode`
 * without an FK; rows are deleted explicitly with their owner and swept for orphans.
 */
@Entity(tableName = "person", indices = [Index("ownerType", "ownerId")])
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerType: OwnerType,
    val ownerId: Long,
    val name: String,
    @ColumnInfo(defaultValue = "'host'") val role: String = "host",
    @ColumnInfo(defaultValue = "'cast'") val grp: String = "cast",
    val imageUrl: String? = null,
    val href: String? = null,
)

/** `funding` (02): same polymorphic-owner shape as `person`. */
@Entity(tableName = "funding", indices = [Index("ownerType", "ownerId")])
data class FundingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerType: OwnerType,
    val ownerId: Long,
    val url: String,
    val label: String? = null,
)

/**
 * `chapter`: one table for every chapter source; writers replace all rows of one
 * `(episodeId, source)` pair in one transaction (02).
 */
@Entity(
    tableName = "chapter",
    primaryKeys = ["episodeId", "source", "ordinal"],
    foreignKeys = [
        ForeignKey(
            EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChapterEntity(
    val episodeId: Long,
    val source: ChapterSource,
    val ordinal: Int,
    val startMs: Long,
    val endMs: Long? = null,
    val title: String? = null,
    val imageUrl: String? = null,
    val linkUrl: String? = null,
    /** Podcasting 2.0 `toc:false`. */
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
)
