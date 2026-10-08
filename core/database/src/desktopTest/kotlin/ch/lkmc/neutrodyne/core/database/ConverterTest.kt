// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

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
import ch.lkmc.neutrodyne.core.model.FilterFlagBits
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
import ch.lkmc.neutrodyne.core.model.YouTubeVariantBits
import ch.lkmc.neutrodyne.core.testing.database.SqlEnumLiterals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 02 Type converters: `name` round-trips for every persisted enum, the documented fallback for an
 * unknown stored name (a value written by a newer build), and the SQL-literal existence check that
 * keeps raw query text aligned with the enum constants. Constants are append-only: adding one
 * keeps this test green; renaming or removing one fails it.
 */
class ConverterTest {
    private val c = NeutrodyneConverters()

    @Test
    fun everyConstantRoundTrips() {
        SourceType.entries.forEach { assertEquals(it, c.toSourceType(c.fromSourceType(it))) }
        PodcastStatus.entries.forEach { assertEquals(it, c.toPodcastStatus(c.fromPodcastStatus(it))) }
        FeedOrder.entries.forEach { assertEquals(it, c.toFeedOrder(c.fromFeedOrder(it))) }
        MediaFilter.entries.forEach { assertEquals(it, c.toMediaFilter(c.fromMediaFilter(it))) }
        GroupKind.entries.forEach { assertEquals(it, c.toGroupKind(c.fromGroupKind(it))) }
        MemberSource.entries.forEach { assertEquals(it, c.toMemberSource(c.fromMemberSource(it))) }
        Availability.entries.forEach { assertEquals(it, c.toAvailability(c.fromAvailability(it))) }
        ChapterSource.entries.forEach { assertEquals(it, c.toChapterSource(c.fromChapterSource(it))) }
        PositionSource.entries.forEach { assertEquals(it, c.toPositionSource(c.fromPositionSource(it))) }
        ContextType.entries.forEach { assertEquals(it, c.toContextType(c.fromContextType(it))) }
        NetworkPolicy.entries.forEach { assertEquals(it, c.toNetworkPolicy(c.fromNetworkPolicy(it))) }
        DeleteAfter.entries.forEach { assertEquals(it, c.toDeleteAfter(c.fromDeleteAfter(it))) }
        DownloadState.entries.forEach { assertEquals(it, c.toDownloadState(c.fromDownloadState(it))) }
        DownloadLane.entries.forEach { assertEquals(it, c.toDownloadLane(c.fromDownloadLane(it))) }
        WaitReason.entries.forEach { assertEquals(it, c.toWaitReason(c.fromWaitReason(it))) }
        DownloadError.entries.forEach { assertEquals(it, c.toDownloadError(c.fromDownloadError(it))) }
        SourceKind.entries.forEach { assertEquals(it, c.toSourceKind(c.fromSourceKind(it))) }
        ImportFormat.entries.forEach { assertEquals(it, c.toImportFormat(c.fromImportFormat(it))) }
        ImportState.entries.forEach { assertEquals(it, c.toImportState(c.fromImportState(it))) }
        ImportItemStatus.entries.forEach { assertEquals(it, c.toImportItemStatus(c.fromImportItemStatus(it))) }
        ImportItemKind.entries.forEach { assertEquals(it, c.toImportItemKind(c.fromImportItemKind(it))) }
        ShowType.entries.forEach { assertEquals(it, c.toShowType(c.fromShowType(it))) }
        EpisodeType.entries.forEach { assertEquals(it, c.toEpisodeType(c.fromEpisodeType(it))) }
        FeedErrorKind.entries.forEach { assertEquals(it, c.toFeedErrorKind(c.fromFeedErrorKind(it))) }
        OwnerType.entries.forEach { assertEquals(it, c.toOwnerType(c.fromOwnerType(it))) }
        AliasReason.entries.forEach { assertEquals(it, c.toAliasReason(c.fromAliasReason(it))) }
        SyncCaptureKind.entries.forEach { assertEquals(it, c.toSyncCaptureKind(c.fromSyncCaptureKind(it))) }
    }

    @Test
    fun unknownNamesReadBackAsTheDocumentedFallback() {
        assertEquals(SourceType.RSS, c.toSourceType("NEWER_BUILD_VALUE"))
        assertEquals(PodcastStatus.ACTIVE, c.toPodcastStatus("X"))
        assertEquals(FeedOrder.NEWEST_FIRST, c.toFeedOrder("X"))
        assertEquals(MediaFilter.ALL, c.toMediaFilter("X"))
        assertEquals(GroupKind.MANUAL, c.toGroupKind("X"))
        assertEquals(MemberSource.MANUAL, c.toMemberSource("X"))
        assertEquals(Availability.UNAVAILABLE, c.toAvailability("X"))
        assertEquals(ChapterSource.PSC, c.toChapterSource("X"))
        assertEquals(PositionSource.STREAM, c.toPositionSource("X"))
        assertNull(c.toContextType("X"), "unknown context reads as no-context")
        assertEquals(NetworkPolicy.UNMETERED, c.toNetworkPolicy("X"))
        assertEquals(DeleteAfter.NEVER, c.toDeleteAfter("X"))
        assertEquals(DownloadState.FAILED, c.toDownloadState("X"))
        assertEquals(DownloadLane.MANUAL, c.toDownloadLane("X"))
        assertEquals(WaitReason.NONE, c.toWaitReason("X"))
        assertEquals(DownloadError.UNKNOWN, c.toDownloadError("X"))
        assertEquals(SourceKind.RSS_ENCLOSURE, c.toSourceKind("X"))
        assertEquals(ImportFormat.OPML, c.toImportFormat("X"))
        assertEquals(ImportState.DONE, c.toImportState("X"))
        assertEquals(ImportItemStatus.FETCH_FAILED, c.toImportItemStatus("X"))
        assertEquals(ImportItemKind.RSS, c.toImportItemKind("X"))
        assertNull(c.toShowType("X"), "unknown show type reads as null")
        assertNull(c.toEpisodeType("X"), "unknown episode type reads as null")
        assertEquals(FeedErrorKind.UNKNOWN, c.toFeedErrorKind("X"))
        assertEquals(OwnerType.EPISODE, c.toOwnerType("X"))
        assertEquals(AliasReason.IMPORT, c.toAliasReason("X"))
        assertEquals(SyncCaptureKind.REPLAY, c.toSyncCaptureKind("X"))
    }

    @Test
    fun nullsStayNull() {
        assertNull(c.toSourceType(null))
        assertNull(c.fromSourceType(null))
        assertNull(c.toAvailability(null))
        assertNull(c.toSyncCaptureKind(null))
    }

    @Test
    fun everySqlLiteralStillExistsAsAnEnumConstant() {
        for (literal in SqlEnumLiterals.ALL) {
            assertTrue(
                literal.name in literal.constants,
                "SQL literal '${literal.name}' is not a ${literal.enumName} constant",
            )
        }
    }

    /** The committed constant sets — removing or renaming one fails the append-only rule. */
    @Test
    fun persistedEnumsKeepTheCommittedNames() {
        val committed: Map<String, Set<String>> =
            mapOf(
                "SourceType" to setOf("RSS", "YOUTUBE_CHANNEL", "YOUTUBE_PLAYLIST"),
                "PodcastStatus" to setOf("PENDING_FIRST_FETCH", "ACTIVE"),
                "FeedOrder" to setOf("NEWEST_FIRST", "OLDEST_FIRST"),
                "MediaFilter" to setOf("ALL", "AUDIO", "VIDEO"),
                "GroupKind" to setOf("MANUAL", "SMART"),
                "MemberSource" to setOf("MANUAL", "RULE"),
                "Availability" to
                    setOf(
                        "AVAILABLE",
                        "UPCOMING",
                        "LIVE",
                        "MEMBERS_ONLY",
                        "AGE_RESTRICTED",
                        "REGION_BLOCKED",
                        "PRIVATE",
                        "KIDS_ONLY",
                        "UNAVAILABLE",
                    ),
                "ChapterSource" to setOf("PODCASTING20_JSON", "PSC", "ID3", "MP4", "YOUTUBE_DESC"),
                "PositionSource" to setOf("STREAM", "DOWNLOAD"),
                "ContextType" to setOf("GROUP", "PODCAST", "ALL", "UNGROUPED", "DOWNLOADS", "EXTERNAL"),
                "NetworkPolicy" to setOf("UNMETERED", "ANY"),
                "DeleteAfter" to setOf("IMMEDIATELY", "AFTER_24H", "NEVER"),
                "DownloadState" to
                    setOf(
                        "QUEUED",
                        "RESOLVING",
                        "DOWNLOADING",
                        "PAUSED",
                        "VERIFYING",
                        "COMPLETED",
                        "MISSING",
                        "FAILED",
                    ),
                "DownloadLane" to setOf("MANUAL", "AUTO"),
                "WaitReason" to
                    setOf(
                        "NONE",
                        "NETWORK",
                        "UNMETERED_NETWORK",
                        "CHARGING",
                        "STORAGE",
                        "BACKOFF",
                        "SYSTEM",
                        "NEEDS_FOREGROUND",
                        "SLOT",
                    ),
                "DownloadError" to
                    setOf(
                        "HTTP_NOT_FOUND",
                        "HTTP_GONE",
                        "HTTP_AUTH",
                        "HTTP_CLIENT",
                        "HTTP_SERVER",
                        "HTTP_RATE_LIMITED",
                        "NETWORK_IO",
                        "NOT_MEDIA",
                        "SIZE_MISMATCH",
                        "STORAGE_FULL",
                        "STORAGE_UNAVAILABLE",
                        "YT_UNAVAILABLE",
                        "YT_EXTRACTION",
                        "YT_FORBIDDEN",
                        "UNSUPPORTED_STREAM",
                        "CANCELLED_BY_SYSTEM",
                        "UNKNOWN",
                    ),
                "SourceKind" to setOf("RSS_ENCLOSURE", "YOUTUBE"),
                "ImportFormat" to
                    setOf("OPML", "NEWPIPE_JSON", "LIBRETUBE_JSON", "TAKEOUT_CSV", "NEUTRODYNE_BACKUP"),
                "ImportState" to setOf("PREVIEW", "COMMITTED", "FETCHING", "DONE", "CANCELLED"),
                "ImportItemStatus" to
                    setOf(
                        "PREVIEW",
                        "QUEUED",
                        "SUBSCRIBED",
                        "ALREADY_SUBSCRIBED",
                        "MERGED",
                        "DUPLICATE_IN_FILE",
                        "INVALID_URL",
                        "NOT_A_FEED",
                        "NO_MEDIA",
                        "AUTH_REQUIRED",
                        "GONE",
                        "FETCH_FAILED",
                        "YOUTUBE_UNSUPPORTED_YET",
                    ),
                "ImportItemKind" to setOf("RSS", "YOUTUBE"),
                "ShowType" to setOf("EPISODIC", "SERIAL"),
                "EpisodeType" to setOf("FULL", "TRAILER", "BONUS"),
                "OwnerType" to setOf("PODCAST", "EPISODE"),
                "AliasReason" to
                    setOf(
                        "SUBSCRIBE_INPUT",
                        "REDIRECT",
                        "NEW_FEED_URL",
                        "IMPORT",
                        "RESTORE",
                        "MERGE",
                        "RENORMALISED",
                        "SYNC",
                    ),
                "SyncCaptureKind" to setOf("LOCAL", "REPLAY"),
            )
        val actual: Map<String, Set<String>> =
            mapOf(
                "SourceType" to names<SourceType>(),
                "PodcastStatus" to names<PodcastStatus>(),
                "FeedOrder" to names<FeedOrder>(),
                "MediaFilter" to names<MediaFilter>(),
                "GroupKind" to names<GroupKind>(),
                "MemberSource" to names<MemberSource>(),
                "Availability" to names<Availability>(),
                "ChapterSource" to names<ChapterSource>(),
                "PositionSource" to names<PositionSource>(),
                "ContextType" to names<ContextType>(),
                "NetworkPolicy" to names<NetworkPolicy>(),
                "DeleteAfter" to names<DeleteAfter>(),
                "DownloadState" to names<DownloadState>(),
                "DownloadLane" to names<DownloadLane>(),
                "WaitReason" to names<WaitReason>(),
                "DownloadError" to names<DownloadError>(),
                "SourceKind" to names<SourceKind>(),
                "ImportFormat" to names<ImportFormat>(),
                "ImportState" to names<ImportState>(),
                "ImportItemStatus" to names<ImportItemStatus>(),
                "ImportItemKind" to names<ImportItemKind>(),
                "ShowType" to names<ShowType>(),
                "EpisodeType" to names<EpisodeType>(),
                "OwnerType" to names<OwnerType>(),
                "AliasReason" to names<AliasReason>(),
                "SyncCaptureKind" to names<SyncCaptureKind>(),
            )
        // FeedErrorKind's 24-name list is owned by 03; require its known anchors only.
        assertTrue(
            setOf("OFFLINE", "IDENTITY_CONFLICT", "UNKNOWN").all { it in names<FeedErrorKind>() },
            "FeedErrorKind lost a committed anchor name",
        )
        for ((enumName, required) in committed) {
            val have = checkNotNull(actual[enumName])
            assertTrue(
                have.containsAll(required),
                "$enumName lost constants: ${required - have} (constants are append-only)",
            )
        }
    }

    @Test
    fun bitMaskConstantsAreDistinctPowersOfTwo() {
        assertEquals(
            setOf(1, 2, 4),
            setOf(YouTubeVariantBits.LONG_FORM, YouTubeVariantBits.SHORTS, YouTubeVariantBits.LIVE),
        )
        assertEquals(
            setOf(1, 2, 4),
            setOf(FilterFlagBits.UNPLAYED, FilterFlagBits.DOWNLOADED, FilterFlagBits.IN_PROGRESS),
        )
    }

    private inline fun <reified E : Enum<E>> names(): Set<String> = enumValues<E>().mapTo(mutableSetOf()) { it.name }
}
