// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data

import androidx.paging.PagingSource
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.refresh.AdapterResult
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshRequest
import ch.lkmc.neutrodyne.core.data.youtube.YouTubeChannelRepositoryImpl
import ch.lkmc.neutrodyne.core.data.youtube.YouTubeSubscriptionWriterImpl
import ch.lkmc.neutrodyne.core.database.EpisodeRowProjection
import ch.lkmc.neutrodyne.core.database.FeedQueryBuilder
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.SubscribeError
import ch.lkmc.neutrodyne.core.domain.YouTubeDuplicatePolicy
import ch.lkmc.neutrodyne.core.domain.YouTubeSubscription
import ch.lkmc.neutrodyne.core.domain.YouTubeSubscriptionDisposition
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.DirectoryHit
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.ProviderId
import ch.lkmc.neutrodyne.core.model.SearchResults
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeSearchRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeChannelMetadata
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.youtube.api.ChannelResolution
import ch.lkmc.neutrodyne.youtube.api.ResolvedVia
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubeSubscriptionTest {
    private val clock = TestClock()
    private val db = newDb(clock)
    private val writer = YouTubeSubscriptionWriterImpl(db, clock, TestOrderKeys)
    private val scheduler = FakeRefreshScheduler()
    private val settings = FakeSettingsRepository()
    private val search = FakeSearchRepository()
    private val repository = YouTubeChannelRepositoryImpl(db, FakeYouTubeChannelMetadata(), scheduler, search, settings)
    private val channel =
        ChannelResolution.Resolved(
            YouTubeFeedUrls.YOUTUBE_CONTROL_CHANNEL_ID,
            "Channel",
            null,
            null,
            "Plain <text>",
            ResolvedVia.HTML,
        )

    @AfterTest fun close() {
        db.close()
    }

    @Test fun canonicalSubscribeRaceCommitsOneRowAndOneMembership() =
        runTest {
            val group = db.groupDao().insert(groupEntity(orderKey = "a"))
            val start = CompletableDeferred<Unit>()
            val results =
                (1..2)
                    .map {
                        async(Dispatchers.Default) {
                            start.await()
                            writer.write(channel, 3, setOf(group), YouTubeDuplicatePolicy.REJECT)
                        }
                    }.also { start.complete(Unit) }
                    .awaitAll()
            val inserted =
                results.filterIsInstance<Outcome.Success<*>>().single().value
                    as YouTubeSubscription
            assertIs<SubscribeError.AlreadySubscribed>(results.filterIsInstance<Outcome.Failure<*>>().single().error)
            val row = assertNotNull(db.podcastDao().byId(inserted.podcastId))
            assertEquals(PodcastStatus.PENDING_FIRST_FETCH, row.status)
            assertTrue(row.initialFetch)
            assertEquals(clock.now(), row.nextRefreshAt)
            assertEquals(SourceType.YOUTUBE_CHANNEL, row.sourceType)
            assertEquals(3, row.youtubeVariants)
            assertEquals(YouTubeFeedUrls.canonical(channel.channelId), row.feedUrl)
            assertEquals(UrlNormalizer.forIdentity(row.feedUrl), row.feedKey)
            assertEquals(36, row.syncId.length)
            assertEquals("Plain &lt;text&gt;", row.descriptionHtml)
            assertNull(row.channelMetadataAt)
            assertEquals(listOf(row.id), db.podcastDao().youtubeChannelIds())
            assertEquals(1, db.groupDao().membersOf(group).size)
        }

    @Test fun importPolicyUnionsGroupsWithoutChangingExistingSettings() =
        runTest {
            val a = db.groupDao().insert(groupEntity(name = "A", orderKey = "a"))
            val b = db.groupDao().insert(groupEntity(name = "B", orderKey = "b"))
            val firstResult = writer.write(channel, 3, setOf(a), YouTubeDuplicatePolicy.REJECT)
            val first = assertIs<Outcome.Success<YouTubeSubscription>>(firstResult).value
            val nextResult =
                writer.write(
                    channel.copy(title = "Changed"),
                    5,
                    setOf(a, b),
                    YouTubeDuplicatePolicy.KEEP_EXISTING,
                    "Custom",
                )
            val next = assertIs<Outcome.Success<YouTubeSubscription>>(nextResult).value
            assertEquals(first.podcastId, next.podcastId)
            assertEquals(YouTubeSubscriptionDisposition.EXISTING, next.disposition)
            val row = assertNotNull(db.podcastDao().byId(first.podcastId))
            assertEquals("Channel", row.title)
            assertEquals(3, row.youtubeVariants)
            assertEquals(1, db.groupDao().membersOf(a).size)
            assertEquals(1, db.groupDao().membersOf(b).size)
        }

    @Test fun confirmedChannelSchedulesNormalRefreshAndFirstFailureKeepsItPending() =
        runTest {
            val root =
                java.nio.file.Files
                    .createTempDirectory("yt-subscribe")
                    .toFile()
            val resolver = newResolver(root, db, clock)
            try {
                val subscribe = newSubscribe(db, resolver.cache, resolver.resolver, scheduler, settings, clock).useCase
                val id = assertIs<Outcome.Success<Long>>(subscribe.youTube(channel, 1, emptySet())).value
                assertEquals(listOf(id), (scheduler.nowRequests.single().scope as RefreshScope.Podcasts).ids)
                assertFalse(scheduler.nowRequests.single().pagesOnly)
                val stub =
                    StubSourceAdapter(onFetch = { _, _ ->
                        AdapterResult.Failed(FeedErrorKind.CONNECTION, null, null, true)
                    })
                newRefresher(db, mapOf(SourceType.YOUTUBE_CHANNEL to stub), clock).run(
                    RefreshRequest(
                        RefreshScope.Podcasts(listOf(id)),
                        false,
                        false,
                        RefreshOrigin.SUBSCRIBE,
                    ),
                )
                val row = assertNotNull(db.podcastDao().byId(id))
                assertEquals(PodcastStatus.PENDING_FIRST_FETCH, row.status)
                assertTrue(row.initialFetch)
                assertFalse(row.gone)
                assertEquals(1, row.failureCount)
            } finally {
                resolver.close()
                root.deleteRecursively()
            }
        }

    @Test fun invalidGroupRollsBackSubscription() =
        runTest {
            assertIs<Outcome.Failure<*>>(writer.write(channel, 1, setOf(Long.MAX_VALUE), YouTubeDuplicatePolicy.REJECT))
            assertTrue(db.podcastDao().youtubeChannelIds().isEmpty())
        }

    @Test fun shortsWriteInvalidatesAnExistingPageAndLiveOffKeepsPastRows() =
        runTest {
            val result = writer.write(channel, 7, emptySet(), YouTubeDuplicatePolicy.REJECT)
            val id = assertIs<Outcome.Success<YouTubeSubscription>>(result).value.podcastId
            db.episodeDao().insertStub(
                episodeEntity(podcastId = id) {
                    copy(isShort = true, availability = Availability.AVAILABLE)
                },
            )
            db.episodeDao().insertStub(
                episodeEntity(podcastId = id) {
                    copy(isShort = false, availability = Availability.AVAILABLE)
                },
            )

            fun page() =
                db.feedDao().page(FeedQueryBuilder.page(FeedSource.Podcast(id), FeedFilters(), FeedOrder.NEWEST_FIRST))

            suspend fun size(source: PagingSource<Int, EpisodeRowProjection>): Int {
                val loaded = source.load(PagingSource.LoadParams.Refresh(null, 20, false))
                return assertIs<PagingSource.LoadResult.Page<Int, EpisodeRowProjection>>(loaded).data.size
            }
            val old = page()
            assertEquals(2, size(old))
            val invalidated = CompletableDeferred<Unit>()
            old.registerInvalidatedCallback { invalidated.complete(Unit) }
            repository.setVariants(id, 1)
            withContext(Dispatchers.Default) { withTimeout(5_000) { invalidated.await() } }
            assertTrue(old.invalid)
            assertEquals(1, size(page()))
            repository.setVariants(id, 3)
            assertEquals(2, size(page()))
            assertEquals(2, scheduler.nowRequests.size)
        }

    @Test fun rssMatchesNfkcArtistAndTimesOutWithoutAffectingSubscriptions() =
        runTest {
            search.searchAnswer = { query ->
                flowOf(
                    SearchResults(
                        query,
                        persistentListOf(
                            DirectoryHit(
                                "https://example.org/rss",
                                "Different",
                                author = "Ｆｏｏ & Bar",
                                providers = persistentSetOf(ProviderId.APPLE),
                            ),
                        ),
                        persistentMapOf(),
                        true,
                    ),
                )
            }
            assertEquals("https://example.org/rss", repository.findRssAlternative("Foo Bar")?.feedUrl)
            settings.set(YouTubeSettingKeys.SUGGEST_RSS, false)
            assertNull(repository.findRssAlternative("Foo Bar"))
            assertEquals(1, search.queries.size)
            settings.set(YouTubeSettingKeys.SUGGEST_RSS, true)
            search.searchAnswer = { flow { kotlinx.coroutines.awaitCancellation() } }
            val before = testScheduler.currentTime
            assertNull(repository.findRssAlternative("Foo Bar"))
            assertEquals(8_000, testScheduler.currentTime - before)
            assertTrue(db.podcastDao().youtubeChannelIds().isEmpty())
        }
}
