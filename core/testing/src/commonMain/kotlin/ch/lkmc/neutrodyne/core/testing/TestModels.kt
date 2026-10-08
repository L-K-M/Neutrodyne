// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.model.AlreadySubscribed
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.EpisodeDetail
import ch.lkmc.neutrodyne.core.model.EpisodeRow
import ch.lkmc.neutrodyne.core.model.EpisodeType
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.FeedHealth
import ch.lkmc.neutrodyne.core.model.FeedInfo
import ch.lkmc.neutrodyne.core.model.FeedMove
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedPreview
import ch.lkmc.neutrodyne.core.model.LibraryTile
import ch.lkmc.neutrodyne.core.model.PodcastDetail
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.ShowNotes
import ch.lkmc.neutrodyne.core.model.ShowType
import ch.lkmc.neutrodyne.core.model.SourceType

/*
 * Deterministic `core:model` fixtures for screen and ViewModel tests (09 Shared helpers). Each
 * builder defaults every field so a test names only the values it asserts on; ids and keys are
 * stable so golden output never drifts between runs.
 */

/** A real cover reference (`u-` key — loaded by the image loader, never painted as a monogram). */
fun testCoverArtwork(
    url: String = "https://example.com/art.jpg",
    version: Int = 1,
): ArtworkRef =
    ArtworkRef(key = "u-" + url.encodeToByteArray().contentHashCode().toString(16), url = url, version = version)

/** A monogram reference (`m-` key — `CoverArt` paints it live, no load). */
fun testMonogramArtwork(seed: String): ArtworkRef =
    ArtworkRef(key = "m-" + seed.encodeToByteArray().contentHashCode().toString(16), url = null, version = 0)

/** A healthy feed — the tile/detail banner's neutral state (03 Per-feed states). */
fun testFeedHealth(
    gone: Boolean = false,
    needsCredentials: Boolean = false,
    failureCount: Int = 0,
    lastSuccessAt: Long? = TestClock.DEFAULT_NOW - HOUR_MS,
    lastErrorKind: FeedErrorKind? = null,
    possiblyDead: Boolean = false,
): FeedHealth =
    FeedHealth(
        gone = gone,
        needsCredentials = needsCredentials,
        failureCount = failureCount,
        lastSuccessAt = lastSuccessAt,
        lastErrorKind = lastErrorKind,
        possiblyDead = possiblyDead,
    )

/** One `LibraryTile`; the AC5/AC10 grid mixes `testCoverArtwork` and `testMonogramArtwork` tiles. */
fun testLibraryTile(
    podcastId: Long,
    displayTitle: String = "Podcast $podcastId",
    sourceType: SourceType = SourceType.RSS,
    status: PodcastStatus = PodcastStatus.ACTIVE,
    artwork: ArtworkRef = testCoverArtwork("https://example.com/art-$podcastId.jpg"),
    artworkAvgArgb: Int? = null,
    health: FeedHealth = testFeedHealth(),
    latestEpisodeAt: Long? = TestClock.DEFAULT_NOW - podcastId * HOUR_MS,
    subscribedAt: Long = TestClock.DEFAULT_NOW - podcastId * DAY_MS,
    unplayedCount: Int = 0,
): LibraryTile =
    LibraryTile(
        podcastId = podcastId,
        displayTitle = displayTitle,
        sourceType = sourceType,
        status = status,
        artwork = artwork,
        artworkAvgArgb = artworkAvgArgb,
        health = health,
        latestEpisodeAt = latestEpisodeAt,
        subscribedAt = subscribedAt,
        unplayedCount = unplayedCount,
    )

/** One feed `EpisodeRow` — [sortDate] and [pubDate] default to [TestClock]'s fixed now. */
fun testEpisodeRow(
    id: Long,
    podcastId: Long = 1,
    title: String = "Episode $id",
    podcastTitle: String = "Podcast $podcastId",
    sortDate: Long = TestClock.DEFAULT_NOW - id * HOUR_MS,
    pubDate: Long? = sortDate,
    durationMs: Long? = 45 * MINUTE_MS,
    isVideo: Boolean = false,
    isShort: Boolean = false,
    availability: Availability = Availability.AVAILABLE,
    episodeType: EpisodeType? = EpisodeType.FULL,
    episodeDisplay: String? = null,
    sourceType: SourceType = SourceType.RSS,
    externalMediaId: String? = null,
    isNew: Boolean = false,
    firstSeenAt: Long = sortDate,
    artwork: ArtworkRef = testCoverArtwork("https://example.com/pod-$podcastId.jpg"),
    podcastArtwork: ArtworkRef = artwork,
    playedAt: Long? = null,
    startedAt: Long? = null,
    isFavorite: Boolean = false,
    downloadState: DownloadState? = null,
): EpisodeRow =
    EpisodeRow(
        id = id,
        podcastId = podcastId,
        title = title,
        podcastTitle = podcastTitle,
        sortDate = sortDate,
        pubDate = pubDate,
        durationMs = durationMs,
        isVideo = isVideo,
        isShort = isShort,
        availability = availability,
        episodeType = episodeType,
        episodeDisplay = episodeDisplay,
        sourceType = sourceType,
        externalMediaId = externalMediaId,
        isNew = isNew,
        firstSeenAt = firstSeenAt,
        artwork = artwork,
        artworkAvgArgb = null,
        podcastArtwork = podcastArtwork,
        podcastArtworkAvgArgb = null,
        playedAt = playedAt,
        startedAt = startedAt,
        isFavorite = isFavorite,
        downloadState = downloadState,
    )

/** A `PodcastDetail`; [episodeOrder] null + [showType] SERIAL exercises 05's oldest-first default. */
fun testPodcastDetail(
    id: Long = 1,
    displayTitle: String = "Podcast $id",
    author: String? = "Author $id",
    description: ShowNotes? = null,
    artwork: ArtworkRef = testCoverArtwork("https://example.com/pod-$id.jpg"),
    bannerUrl: String? = null,
    sourceType: SourceType = SourceType.RSS,
    link: String? = "https://example.com/pod-$id",
    episodeCount: Int = 3,
    latestEpisodeAt: Long? = TestClock.DEFAULT_NOW - DAY_MS,
    status: PodcastStatus = PodcastStatus.ACTIVE,
    health: FeedHealth = testFeedHealth(),
    isPrivate: Boolean = false,
    episodeOrder: FeedOrder? = null,
    showType: ShowType? = ShowType.EPISODIC,
    hasOlderPages: Boolean = false,
): PodcastDetail =
    PodcastDetail(
        id = id,
        displayTitle = displayTitle,
        author = author,
        description = description,
        artwork = artwork,
        bannerUrl = bannerUrl,
        sourceType = sourceType,
        link = link,
        episodeCount = episodeCount,
        latestEpisodeAt = latestEpisodeAt,
        status = status,
        health = health,
        isPrivate = isPrivate,
        episodeOrder = episodeOrder,
        showType = showType,
        hasOlderPages = hasOlderPages,
    )

/** A `FeedInfo` for the podcast settings feed section. */
fun testFeedInfo(
    feedUrl: String = "https://example.com/feed.xml",
    redactedUrl: String = feedUrl,
    isPrivate: Boolean = false,
    moves: List<FeedMove> = emptyList(),
    lastAttemptAt: Long? = TestClock.DEFAULT_NOW - HOUR_MS,
    lastSuccessAt: Long? = TestClock.DEFAULT_NOW - HOUR_MS,
    nextRefreshAt: Long? = TestClock.DEFAULT_NOW + 23 * HOUR_MS,
    lastErrorKind: FeedErrorKind? = null,
    lastErrorDetail: String? = null,
    pendingNewFeedUrl: String? = null,
): FeedInfo =
    FeedInfo(
        feedUrl = feedUrl,
        redactedUrl = redactedUrl,
        isPrivate = isPrivate,
        moves = moves,
        lastAttemptAt = lastAttemptAt,
        lastSuccessAt = lastSuccessAt,
        nextRefreshAt = nextRefreshAt,
        lastErrorKind = lastErrorKind,
        lastErrorDetail = lastErrorDetail,
        pendingNewFeedUrl = pendingNewFeedUrl,
    )

/** An `EpisodeDetail` for the episode screen. */
fun testEpisodeDetail(
    id: Long = 1,
    podcastId: Long = 1,
    podcastTitle: String = "Podcast $podcastId",
    title: String = "Episode $id",
    pubDate: Long? = TestClock.DEFAULT_NOW - DAY_MS,
    durationMs: Long? = 45 * MINUTE_MS,
    artwork: ArtworkRef = testCoverArtwork("https://example.com/pod-$podcastId.jpg"),
    isVideo: Boolean = false,
    sourceType: SourceType = SourceType.RSS,
    externalMediaId: String? = null,
    availability: Availability? = Availability.AVAILABLE,
    episodeDisplay: String? = null,
    link: String? = "https://example.com/ep-$id",
    playedAt: Long? = null,
    isFavorite: Boolean = false,
    downloadState: DownloadState? = null,
): EpisodeDetail =
    EpisodeDetail(
        id = id,
        podcastId = podcastId,
        podcastTitle = podcastTitle,
        title = title,
        pubDate = pubDate,
        durationMs = durationMs,
        artwork = artwork,
        isVideo = isVideo,
        sourceType = sourceType,
        externalMediaId = externalMediaId,
        availability = availability,
        episodeDisplay = episodeDisplay,
        link = link,
        playedAt = playedAt,
        isFavorite = isFavorite,
        downloadState = downloadState,
    )

/** An add-sheet `FeedPreview`; [previewId] is what `subscribe()` hands the use case. */
fun testFeedPreview(
    previewId: String = "preview-1",
    feedUrl: String = "https://example.com/feed",
    title: String = "A Show",
    author: String? = "An Author",
    artworkUrl: String? = null,
    episodeCount: Int = 3,
    latestEpisodeAt: Long? = TestClock.DEFAULT_NOW - DAY_MS,
    isPrivate: Boolean = false,
    alreadySubscribed: AlreadySubscribed? = null,
    emptyFeed: Boolean = false,
): FeedPreview =
    FeedPreview(
        previewId = previewId,
        feedUrl = feedUrl,
        title = title,
        author = author,
        description = null,
        artworkUrl = artworkUrl,
        link = null,
        categories = emptyList(),
        language = "en",
        explicit = null,
        episodeCount = episodeCount,
        latestEpisodeAt = latestEpisodeAt,
        episodes = emptyList(),
        hasOlderPages = false,
        isPrivate = isPrivate,
        alreadySubscribed = alreadySubscribed,
        emptyFeed = emptyFeed,
    )

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
