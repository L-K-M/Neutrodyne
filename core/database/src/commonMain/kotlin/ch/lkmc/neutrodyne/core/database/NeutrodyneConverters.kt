// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.ColumnTypeConverter
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.DeleteAfter
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.EpisodeType
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.GroupKind
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.MemberSource
import ch.lkmc.neutrodyne.core.model.NetworkPolicy
import ch.lkmc.neutrodyne.core.model.OwnerType
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.ShowType
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.WaitReason

/**
 * The database's one converter class (02 Type converters): `Enum.name` ↔ `TEXT`, one explicit pair
 * per persisted enum. Reading an unknown name returns the documented fallback instead of throwing,
 * so a value written by a newer build never crashes list rendering. Enum constants are only ever
 * **appended** — renaming or removing one requires a data migration.
 */
@Suppress("TooManyFunctions")
class NeutrodyneConverters {
    @ColumnTypeConverter fun fromSourceType(v: SourceType?): String? = v?.name

    @ColumnTypeConverter fun toSourceType(v: String?): SourceType? = v?.let { enumOr(it, SourceType.RSS) }

    @ColumnTypeConverter fun fromPodcastStatus(v: PodcastStatus?): String? = v?.name

    @ColumnTypeConverter fun toPodcastStatus(v: String?): PodcastStatus? = v?.let { enumOr(it, PodcastStatus.ACTIVE) }

    @ColumnTypeConverter fun fromFeedOrder(v: FeedOrder?): String? = v?.name

    @ColumnTypeConverter fun toFeedOrder(v: String?): FeedOrder? = v?.let { enumOr(it, FeedOrder.NEWEST_FIRST) }

    @ColumnTypeConverter fun fromMediaFilter(v: MediaFilter?): String? = v?.name

    @ColumnTypeConverter fun toMediaFilter(v: String?): MediaFilter? = v?.let { enumOr(it, MediaFilter.ALL) }

    @ColumnTypeConverter fun fromGroupKind(v: GroupKind?): String? = v?.name

    @ColumnTypeConverter fun toGroupKind(v: String?): GroupKind? = v?.let { enumOr(it, GroupKind.MANUAL) }

    @ColumnTypeConverter fun fromMemberSource(v: MemberSource?): String? = v?.name

    @ColumnTypeConverter fun toMemberSource(v: String?): MemberSource? = v?.let { enumOr(it, MemberSource.MANUAL) }

    @ColumnTypeConverter fun fromAvailability(v: Availability?): String? = v?.name

    @ColumnTypeConverter fun toAvailability(v: String?): Availability? = v?.let { enumOr(it, Availability.UNAVAILABLE) }

    @ColumnTypeConverter fun fromChapterSource(v: ChapterSource?): String? = v?.name

    @ColumnTypeConverter fun toChapterSource(v: String?): ChapterSource? = v?.let { enumOr(it, ChapterSource.PSC) }

    @ColumnTypeConverter fun fromPositionSource(v: PositionSource?): String? = v?.name

    @ColumnTypeConverter fun toPositionSource(v: String?): PositionSource? =
        v?.let {
            enumOr(
                it,
                PositionSource.STREAM,
            )
        }

    /** Unknown context names fall back to `null` — no context (02 Type converters). */
    @ColumnTypeConverter fun fromContextType(v: ContextType?): String? = v?.name

    @ColumnTypeConverter fun toContextType(v: String?): ContextType? = v?.let { enumOrNull<ContextType>(it) }

    @ColumnTypeConverter fun fromNetworkPolicy(v: NetworkPolicy?): String? = v?.name

    @ColumnTypeConverter fun toNetworkPolicy(v: String?): NetworkPolicy? =
        v?.let {
            enumOr(
                it,
                NetworkPolicy.UNMETERED,
            )
        }

    @ColumnTypeConverter fun fromDeleteAfter(v: DeleteAfter?): String? = v?.name

    @ColumnTypeConverter fun toDeleteAfter(v: String?): DeleteAfter? = v?.let { enumOr(it, DeleteAfter.NEVER) }

    @ColumnTypeConverter fun fromDownloadState(v: DownloadState?): String? = v?.name

    @ColumnTypeConverter fun toDownloadState(v: String?): DownloadState? = v?.let { enumOr(it, DownloadState.FAILED) }

    @ColumnTypeConverter fun fromDownloadLane(v: DownloadLane?): String? = v?.name

    @ColumnTypeConverter fun toDownloadLane(v: String?): DownloadLane? = v?.let { enumOr(it, DownloadLane.MANUAL) }

    @ColumnTypeConverter fun fromWaitReason(v: WaitReason?): String? = v?.name

    @ColumnTypeConverter fun toWaitReason(v: String?): WaitReason? = v?.let { enumOr(it, WaitReason.NONE) }

    @ColumnTypeConverter fun fromDownloadError(v: DownloadError?): String? = v?.name

    @ColumnTypeConverter fun toDownloadError(v: String?): DownloadError? = v?.let { enumOr(it, DownloadError.UNKNOWN) }

    @ColumnTypeConverter fun fromSourceKind(v: SourceKind?): String? = v?.name

    @ColumnTypeConverter fun toSourceKind(v: String?): SourceKind? = v?.let { enumOr(it, SourceKind.RSS_ENCLOSURE) }

    @ColumnTypeConverter fun fromImportFormat(v: ImportFormat?): String? = v?.name

    @ColumnTypeConverter fun toImportFormat(v: String?): ImportFormat? = v?.let { enumOr(it, ImportFormat.OPML) }

    @ColumnTypeConverter fun fromImportState(v: ImportState?): String? = v?.name

    @ColumnTypeConverter fun toImportState(v: String?): ImportState? = v?.let { enumOr(it, ImportState.DONE) }

    @ColumnTypeConverter fun fromImportItemStatus(v: ImportItemStatus?): String? = v?.name

    @ColumnTypeConverter fun toImportItemStatus(v: String?): ImportItemStatus? =
        v?.let {
            enumOr(
                it,
                ImportItemStatus.FETCH_FAILED,
            )
        }

    @ColumnTypeConverter fun fromImportItemKind(v: ImportItemKind?): String? = v?.name

    @ColumnTypeConverter fun toImportItemKind(v: String?): ImportItemKind? = v?.let { enumOr(it, ImportItemKind.RSS) }

    /** Unknown `ShowType`/`EpisodeType` names fall back to `null` (02 Type converters). */
    @ColumnTypeConverter fun fromShowType(v: ShowType?): String? = v?.name

    @ColumnTypeConverter fun toShowType(v: String?): ShowType? = v?.let { enumOrNull<ShowType>(it) }

    @ColumnTypeConverter fun fromEpisodeType(v: EpisodeType?): String? = v?.name

    @ColumnTypeConverter fun toEpisodeType(v: String?): EpisodeType? = v?.let { enumOrNull<EpisodeType>(it) }

    @ColumnTypeConverter fun fromFeedErrorKind(v: FeedErrorKind?): String? = v?.name

    @ColumnTypeConverter fun toFeedErrorKind(v: String?): FeedErrorKind? = v?.let { enumOr(it, FeedErrorKind.UNKNOWN) }

    @ColumnTypeConverter fun fromOwnerType(v: OwnerType?): String? = v?.name

    @ColumnTypeConverter fun toOwnerType(v: String?): OwnerType? = v?.let { enumOr(it, OwnerType.EPISODE) }

    @ColumnTypeConverter fun fromAliasReason(v: AliasReason?): String? = v?.name

    @ColumnTypeConverter fun toAliasReason(v: String?): AliasReason? = v?.let { enumOr(it, AliasReason.IMPORT) }

    /**
     * `sync_outbox.captureKind` (`:core:database`'s own enum, not in the 02 fallback table): an
     * unknown name written by a newer build reads as `REPLAY` — immutable in [restampAbove], the
     * safer misread than `LOCAL`.
     */
    @ColumnTypeConverter fun fromSyncCaptureKind(v: SyncCaptureKind?): String? = v?.name

    @ColumnTypeConverter fun toSyncCaptureKind(v: String?): SyncCaptureKind? =
        v?.let {
            enumOr(
                it,
                SyncCaptureKind.REPLAY,
            )
        }
}

/** `name` → the enum constant, or [fallback] when a newer build wrote an unknown name. */
inline fun <reified E : Enum<E>> enumOr(
    name: String,
    fallback: E,
): E = enumValues<E>().firstOrNull { it.name == name } ?: fallback

/** `name` → the enum constant, or `null` when the name is unknown (the `null` fallbacks of 02). */
inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }
