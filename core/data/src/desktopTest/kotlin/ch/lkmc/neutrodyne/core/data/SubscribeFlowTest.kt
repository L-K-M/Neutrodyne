// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.database.PodcastGroupEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.AddResolution
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.SubscribeError
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.model.Paging
import ch.lkmc.neutrodyne.feeds.parse.FeedParser
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.junit4.MockWebServerRule
import org.junit.Rule

/**
 * 03 Subscribe transaction and Unsubscribe — the M1a write paths end to end over the real
 * resolver (MockWebServer), the real ingest and the fake scheduler: row and alias writes, the
 * `INITIAL` ingest, group memberships, the pending-paging kick, dedupe failure modes, the
 * expired-preview re-fetch and the cascade. Includes the AC11 sync-inert assertions.
 */
class SubscribeFlowTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    private val clock = TestClock()
    private val db = newDb(clock)
    private val settings = FakeSettingsRepository()
    private val scheduler = FakeRefreshScheduler()

    private lateinit var root: File
    private lateinit var resolverBundle: ResolverBundle
    private lateinit var subscribeBundle: SubscribeBundle

    @BeforeTest
    fun setUp() {
        root = kotlin.io.path.createTempDirectory("nd-subscribe-test").toFile()
        resolverBundle = newResolver(root, db, clock)
        subscribeBundle =
            newSubscribe(db, resolverBundle.cache, resolverBundle.resolver, scheduler, settings, clock)
    }

    @AfterTest
    fun tearDown() {
        resolverBundle.close()
        root.deleteRecursively()
    }

    private fun feedUrl(path: String = "/feed.xml") = server.url(path).toString()

    private suspend fun resolvePreview(
        path: String = "/feed.xml",
        body: String = rssBody(items = arrayOf(rssItem("e1"), rssItem("e2"))),
    ): String {
        server.enqueue(mockResponse(body = body))
        val feed = assertIs<AddResolution.Feed>(resolverBundle.resolver.resolve(feedUrl(path)))
        return feed.preview.previewId
    }

    // --- The subscribe transaction (03 Subscribe transaction) -----------------------------------------

    @Test
    fun subscribeWritesTheRowAliasesAndInitialIngest() =
        runTest {
            val previewId = resolvePreview()

            val outcome = subscribeBundle.useCase(previewId, emptySet())

            val id = assertIs<Outcome.Success<Long>>(outcome).value
            val row = assertNotNull(db.podcastDao().byId(id))
            assertEquals(feedUrl(), row.feedUrl)
            assertEquals(UrlNormalizer.forIdentity(feedUrl()), row.feedKey)
            assertEquals("Test Show", row.title)
            assertEquals(PodcastStatus.ACTIVE, row.status)
            assertEquals(NOW, row.subscribedAt)
            assertEquals(NOW, row.lastSuccessAt)
            assertEquals(FeedParser.VERSION, row.parserVersion)
            assertTrue(row.lastParseOk)
            assertTrue((row.nextRefreshAt ?: 0) > NOW)
            // The M1a deferrals: no credential row and no pinned-artwork version yet.
            assertNull(row.credentialId)
            // The INITIAL ingest landed the two items.
            assertEquals(2, db.podcastDao().episodeCount(id))
            assertNotNull(db.episodeDao().byIdentityKey(id, "g:e1"))
            // Nothing else to alias: input URL == feed URL (no redirect hops).
            assertNull(db.podcastDao().aliasOwner(row.feedKey))
            // The periodic tick is rebased once; no pending pages to kick.
            assertEquals(1, scheduler.rescheduleCount)
            assertTrue(scheduler.nowRequests.isEmpty())
            // The preview entry is dropped at the end of the transaction (03 step 4).
            assertNull(resolverBundle.cache.get(previewId))
            // AC11: sync groundwork stays inert.
            assertSyncInert()
        }

    @Test
    fun subscribeEmitsTheInitialFetchAnnouncement() =
        runTest {
            val previewId = resolvePreview()
            val emitted = backgroundScope.async { subscribeBundle.eventBus.newEpisodes.first() }

            val id = assertIs<Outcome.Success<Long>>(subscribeBundle.useCase(previewId, emptySet())).value

            val event = emitted.await()
            assertEquals(id, event.podcastId)
            assertTrue(event.initialFetch)
            assertEquals(2, event.episodeIds.size)
        }

    @Test
    fun redirectHopsBecomeAliases() =
        runTest {
            server.enqueue(mockResponse(301, "", "Location" to "/new.xml"))
            server.enqueue(mockResponse(body = rssBody(items = arrayOf(rssItem("e1")))))
            val feed = assertIs<AddResolution.Feed>(resolverBundle.resolver.resolve(feedUrl("/old.xml")))

            val outcome = subscribeBundle.useCase(feed.preview.previewId, emptySet())

            val id = assertIs<Outcome.Success<Long>>(outcome).value
            val row = assertNotNull(db.podcastDao().byId(id))
            assertEquals(feedUrl("/new.xml"), row.feedUrl)
            // The typed URL was fetched but redirects; it aliases as SUBSCRIBE_INPUT (03 step 3.3).
            val inputKey = assertNotNull(UrlNormalizer.forIdentity(feedUrl("/old.xml")))
            assertEquals(id, db.podcastDao().aliasOwner(inputKey))
        }

    @Test
    fun groupMembershipsAreWrittenInTheTransaction() =
        runTest {
            val groupId =
                db.groupDao()
                    .insert(
                        PodcastGroupEntity(
                            uuid = "g-1",
                            name = "Favourites",
                            nameKey = "favourites",
                            orderKey = "a",
                            createdAt = NOW,
                            updatedAt = NOW,
                        ),
                    )
            val previewId = resolvePreview()

            val id =
                assertIs<Outcome.Success<Long>>(subscribeBundle.useCase(previewId, setOf(groupId)))
                    .value

            val member = db.groupDao().membersOf(groupId).single()
            assertEquals(id, member.podcastId)
            assertEquals(NOW, member.addedAt)
            assertTrue(member.orderKey.isNotEmpty())
        }

    @Test
    fun alreadySubscribedFailsInsideTheTransaction() =
        runTest {
            val previewId = resolvePreview()
            assertIs<Outcome.Success<Long>>(subscribeBundle.useCase(previewId, emptySet()))

            // Resolve the same feed again: dedupe flags it, and a forced subscribe hits the
            // in-transaction feedKey check.
            server.enqueue(mockResponse(body = rssBody()))
            val again =
                assertIs<AddResolution.Feed>(resolverBundle.resolver.resolve(feedUrl()))
            assertNotNull(again.preview.alreadySubscribed)

            val outcome = subscribeBundle.useCase(again.preview.previewId, emptySet())

            assertIs<Outcome.Failure<SubscribeError>>(outcome)
            assertIs<SubscribeError.AlreadySubscribed>((outcome as Outcome.Failure).error)
            assertEquals(1, db.podcastDao().ungroupedIds().size)
        }

    @Test
    fun expiredPreviewIsRefetchedAndSubscribed() =
        runTest {
            val previewId = resolvePreview()
            // The cache TTL is 15 minutes (03 Preview and dedupe); push the clock past it.
            clock.nowMs += 16 * 60_000L
            assertNull(resolverBundle.cache.get(previewId))
            server.enqueue(mockResponse(body = rssBody(items = arrayOf(rssItem("e3")))))

            val outcome = subscribeBundle.useCase(previewId, emptySet())

            assertIs<Outcome.Success<Long>>(outcome)
            assertEquals(2, server.requestCount)
            assertEquals(1, db.podcastDao().episodeCount(assertIs<Outcome.Success<Long>>(outcome).value))
        }

    @Test
    fun failedRefetchSurfacesTheFetchError() =
        runTest {
            val previewId = resolvePreview()
            clock.nowMs += 16 * 60_000L
            server.enqueue(mockResponse(code = 404, body = "gone"))

            val outcome = subscribeBundle.useCase(previewId, emptySet())

            val failure = assertIs<Outcome.Failure<SubscribeError>>(outcome)
            val error = assertIs<SubscribeError.Fetch>(failure.error)
            assertEquals(AddPodcastError.Http(404), error.error)
        }

    @Test
    fun noMediaPreviewFailsSubscribe() =
        runTest {
            // A hand-cached preview whose items have nothing playable (03 Subscribe step 2).
            resolverBundle.cache.put(
                previewId = "https://a.example.com/f",
                inputUrl = "https://a.example.com/f",
                feed = parsedFeed(items = listOf(parsedEpisode(0, enclosureUrl = null))),
                meta = fetchMeta(finalUrl = "https://a.example.com/f"),
                hops = emptyList(),
                credentials = null,
            )

            val outcome = subscribeBundle.useCase("https://a.example.com/f", emptySet())

            assertEquals(
                SubscribeError.NoMedia,
                assertIs<Outcome.Failure<SubscribeError>>(outcome).error,
            )
        }

    // --- Paging kick (03 Subscribe transaction, RFC 5005 paging) --------------------------------------

    @Test
    fun backfillSettingTurnsPagedFeedsIntoAPendingSession() =
        runTest {
            settings.set(FeedsSettingKeys.BACKFILL_PAGED_FEEDS, true)
            resolverBundle.cache.put(
                previewId = "https://a.example.com/f",
                inputUrl = "https://a.example.com/f",
                feed =
                    parsedFeed(items = listOf(parsedEpisode(0, guid = "e1")))
                        .copy(paging = Paging(next = "https://a.example.com/f?page=2")),
                meta = fetchMeta(finalUrl = "https://a.example.com/f"),
                hops = emptyList(),
                credentials = null,
            )

            val id =
                assertIs<Outcome.Success<Long>>(
                        subscribeBundle.useCase("https://a.example.com/f", emptySet()),
                    ).value

            val row = assertNotNull(db.podcastDao().byId(id))
            assertEquals("https://a.example.com/f?page=2", row.pagingNextUrl)
            assertEquals(false, row.pagingComplete)
            val kicked = scheduler.nowRequests.single()
            assertEquals(RefreshScope.Podcasts(listOf(id)), kicked.scope)
            assertTrue(kicked.pagesOnly)
            assertEquals(RefreshOrigin.SUBSCRIBE, kicked.origin)
        }

    @Test
    fun backfillOffLeavesAPagedFeedComplete() =
        runTest {
            settings.set(FeedsSettingKeys.BACKFILL_PAGED_FEEDS, false)
            resolverBundle.cache.put(
                previewId = "https://a.example.com/f",
                inputUrl = "https://a.example.com/f",
                feed =
                    parsedFeed(items = listOf(parsedEpisode(0, guid = "e1")))
                        .copy(paging = Paging(next = "https://a.example.com/f?page=2")),
                meta = fetchMeta(finalUrl = "https://a.example.com/f"),
                hops = emptyList(),
                credentials = null,
            )

            val id =
                assertIs<Outcome.Success<Long>>(
                        subscribeBundle.useCase("https://a.example.com/f", emptySet()),
                    ).value

            val row = assertNotNull(db.podcastDao().byId(id))
            assertEquals("https://a.example.com/f?page=2", row.pagingNextUrl)
            // "not wanted": the link stays for UI but the row completes (03 paging columns).
            assertEquals(true, row.pagingComplete)
            assertTrue(scheduler.nowRequests.isEmpty())
        }

    // --- Unsubscribe (03 Unsubscribe) -----------------------------------------------------------------

    @Test
    fun unsubscribeCascadesEveryOwnedRow() =
        runTest {
            val groupId =
                db.groupDao()
                    .insert(
                        PodcastGroupEntity(
                            uuid = "g-1",
                            name = "G",
                            nameKey = "g",
                            orderKey = "a",
                            createdAt = NOW,
                            updatedAt = NOW,
                        ),
                    )
            val previewId = resolvePreview()
            val id =
                assertIs<Outcome.Success<Long>>(subscribeBundle.useCase(previewId, setOf(groupId)))
                    .value
            val before = scheduler.rescheduleCount

            val removed = newUnsubscribe(db, scheduler, clock)(listOf(id))

            assertEquals(listOf(id), removed)
            assertNull(db.podcastDao().byId(id))
            assertEquals(0, db.podcastDao().episodeCount(id))
            assertNull(db.podcastDao().aliasOwner(UrlNormalizer.forIdentity(feedUrl()) ?: ""))
            assertTrue(db.groupDao().membersOf(groupId).isEmpty())
            assertTrue(scheduler.rescheduleCount > before)
            assertSyncInert()
        }

    @Test
    fun unsubscribeSkipsMissingIdsAndRemovesTheRest() =
        runTest {
            val a = seedPodcast(db, "https://a.example.com/f")
            val b = seedPodcast(db, "https://b.example.com/f")

            val removed = newUnsubscribe(db, scheduler, clock)(listOf(a, 999_999L, b))

            assertEquals(listOf(a, b), removed)
            assertNull(db.podcastDao().byId(a))
            assertNull(db.podcastDao().byId(b))
        }

    @Test
    fun emptyUnsubscribeDoesNotReschedule() =
        runTest {
            val removed = newUnsubscribe(db, scheduler, clock)(listOf(42L))

            assertTrue(removed.isEmpty())
            assertEquals(0, scheduler.rescheduleCount)
        }

    private suspend fun assertSyncInert() {
        assertEquals(0, db.syncOutboxDao().count())
        assertEquals(0, db.syncClockDao().count())
        assertEquals(0, db.syncParkedDao().count())
        assertEquals(0, db.syncHeldDao().count())
        assertEquals(SyncStateEntity(), db.syncStateDao().get(), "sync_state stays at defaults")
    }

    private companion object {
        const val NOW = TestClock.DEFAULT_NOW
    }
}
