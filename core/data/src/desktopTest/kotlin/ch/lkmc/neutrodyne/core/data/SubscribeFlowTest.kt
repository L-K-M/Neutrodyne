// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.useReaderConnection
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.fetch.RedirectHop
import ch.lkmc.neutrodyne.core.data.ingest.IngestContext
import ch.lkmc.neutrodyne.core.data.ingest.IngestMode
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.database.EpisodeDescriptionCodec
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastGroupEntity
import ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.AddResolution
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.SubscribeError
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.jvm.parse.XmlPullFeedParser
import ch.lkmc.neutrodyne.feeds.model.Paging
import ch.lkmc.neutrodyne.feeds.model.ParsedFeed
import ch.lkmc.neutrodyne.feeds.model.WarningCode
import ch.lkmc.neutrodyne.feeds.parse.FeedParser
import ch.lkmc.neutrodyne.feeds.parse.ParseLimits
import ch.lkmc.neutrodyne.feeds.parse.ParseResult
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.junit4.MockWebServerRule
import okio.Buffer
import org.junit.Rule
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        root =
            kotlin.io.path
                .createTempDirectory("nd-subscribe-test")
                .toFile()
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
                db
                    .groupDao()
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

    @Test
    fun aTemporaryRedirectKeepsTheRequestedUrlAsIdentity() =
        runTest {
            server.enqueue(mockResponse(302, "", "Location" to "/temp.xml"))
            server.enqueue(mockResponse(body = rssBody(items = arrayOf(rssItem("e1")))))
            val feed =
                assertIs<AddResolution.Feed>(resolverBundle.resolver.resolve(feedUrl("/old.xml")))

            val id =
                assertIs<Outcome.Success<Long>>(subscribeBundle.useCase(feed.preview.previewId, emptySet()))
                    .value

            val row = assertNotNull(db.podcastDao().byId(id))
            // `permanentUrl ?: requestedUrl` (03 Subscribe): a 302's target is the final fetch URL,
            // never the subscription identity — the expiring CDN URL must not become `feedUrl`.
            assertEquals(feedUrl("/old.xml"), row.feedUrl)
            assertEquals(UrlNormalizer.forIdentity(feedUrl("/old.xml")), row.feedKey)
        }

    @Test
    fun aTemporaryRedirectTargetDoesNotReuseTheRedirectedPreview() =
        runTest {
            // A 302s to C: the cached entry's identity stays A. `preview(C)` must fetch C fresh —
            // reusing A's entry would hand the subscribe transaction A's identity for C's feed.
            server.enqueue(mockResponse(302, "", "Location" to "/cdn.xml"))
            server.enqueue(mockResponse(body = rssBody(items = arrayOf(rssItem("e1")))))
            val feedA =
                assertIs<AddResolution.Feed>(resolverBundle.resolver.resolve(feedUrl("/a.xml")))
            assertEquals(feedUrl("/a.xml"), feedA.preview.previewId)

            server.enqueue(mockResponse(body = rssBody(items = arrayOf(rssItem("e2")))))
            val previewC =
                assertIs<Outcome.Success<ch.lkmc.neutrodyne.core.model.FeedPreview>>(
                    resolverBundle.resolver.preview(feedUrl("/cdn.xml")),
                ).value

            assertEquals(feedUrl("/cdn.xml"), previewC.feedUrl)
            assertEquals(feedUrl("/cdn.xml"), previewC.previewId)
            assertEquals(3, server.requestCount)

            val id =
                assertIs<Outcome.Success<Long>>(subscribeBundle.useCase(previewC.previewId, emptySet()))
                    .value
            val row = assertNotNull(db.podcastDao().byId(id))
            assertEquals(feedUrl("/cdn.xml"), row.feedUrl)
            assertEquals(UrlNormalizer.forIdentity(feedUrl("/cdn.xml")), row.feedKey)
        }

    @Test
    fun aTemporaryRedirectTerminalUrlDedupesAtCommit() =
        runTest {
            // C is subscribed directly while A's cached preview still sits in the cache (it fetched
            // through a 302 onto C). Committing A must dedupe on the terminal URL — otherwise the
            // subscribe slips a duplicate catalogue in under A (03 step 3.1).
            server.enqueue(mockResponse(302, "", "Location" to "/cdn.xml"))
            server.enqueue(mockResponse(body = rssBody(items = arrayOf(rssItem("e1")))))
            val feedA =
                assertIs<AddResolution.Feed>(resolverBundle.resolver.resolve(feedUrl("/a.xml")))

            server.enqueue(mockResponse(body = rssBody(items = arrayOf(rssItem("e1")))))
            val feedC =
                assertIs<AddResolution.Feed>(resolverBundle.resolver.resolve(feedUrl("/cdn.xml")))
            val idC =
                assertIs<Outcome.Success<Long>>(
                    subscribeBundle.useCase(feedC.preview.previewId, emptySet()),
                ).value

            val outcome = subscribeBundle.useCase(feedA.preview.previewId, emptySet())

            assertEquals(
                SubscribeError.AlreadySubscribed(idC),
                assertIs<Outcome.Failure<SubscribeError>>(outcome).error,
            )
            assertNull(
                db.podcastDao().byFeedKey(UrlNormalizer.forIdentity(feedUrl("/a.xml"))!!),
            )
            assertEquals(1, db.podcastDao().ungroupedIds().size)
            assertEquals(1, db.podcastDao().episodeCount(idC))
        }

    @Test
    fun aRedirectHopOwnedAsAFeedKeyDedupes() =
        runTest {
            // Another podcast already owns a hop URL as its PRIMARY feedKey — the in-transaction
            // dedupe checks feedKeys as well as aliases for every relevant URL (S4).
            val owned = "https://cdn.example.com/owned.xml"
            val existing =
                seedPodcast(db, feedUrl = owned, feedKey = UrlNormalizer.forIdentity(owned)!!)
            resolverBundle.cache.put(
                previewId = "https://a.example.com/f",
                inputUrl = "https://a.example.com/f",
                feed = parsedFeed(items = listOf(parsedEpisode(0, guid = "e1"))),
                meta =
                    fetchMeta(finalUrl = "https://b.example.com/f")
                        .copy(requestedUrl = "https://a.example.com/f"),
                hops =
                    listOf(
                        RedirectHop(url = "https://a.example.com/f", status = 301),
                        RedirectHop(url = owned, status = 301),
                    ),
                credentials = null,
            )

            val outcome = subscribeBundle.useCase("https://a.example.com/f", emptySet())

            assertEquals(
                SubscribeError.AlreadySubscribed(existing),
                assertIs<Outcome.Failure<SubscribeError>>(outcome).error,
            )
            assertNull(db.podcastDao().byFeedKey(UrlNormalizer.forIdentity("https://a.example.com/f")!!))
        }

    @Test
    fun aRedirectHopOwnedAsAnAliasDedupes() =
        runTest {
            val owned = "https://cdn.example.com/hop-alias.xml"
            val existing = seedPodcast(db, feedUrl = "https://b.example.com/f")
            db
                .podcastDao()
                .insertAliases(
                    listOf(
                        PodcastUrlAliasEntity(
                            UrlNormalizer.forIdentity(owned)!!,
                            existing,
                            AliasReason.REDIRECT,
                            NOW,
                        ),
                    ),
                )
            resolverBundle.cache.put(
                previewId = "https://a.example.com/f",
                inputUrl = "https://a.example.com/f",
                feed = parsedFeed(items = listOf(parsedEpisode(0, guid = "e1"))),
                meta =
                    fetchMeta(finalUrl = "https://c.example.com/f")
                        .copy(requestedUrl = "https://a.example.com/f"),
                hops = listOf(RedirectHop(url = owned, status = 302)),
                credentials = null,
            )

            val outcome = subscribeBundle.useCase("https://a.example.com/f", emptySet())

            assertEquals(
                SubscribeError.AlreadySubscribed(existing),
                assertIs<Outcome.Failure<SubscribeError>>(outcome).error,
            )
        }

    @Test
    fun anErrorValuedInitialIngestRollsBackTheSubscription() =
        runTest {
            // A list feed that slipped past the resolver's guards: the ingest's `emptyKind`
            // must roll the subscription back instead of committing an ACTIVE row (S14).
            resolverBundle.cache.put(
                previewId = "https://a.example.com/list",
                inputUrl = "https://a.example.com/list",
                feed = parsedFeed(items = emptyList(), medium = "podcastL"),
                meta = fetchMeta(finalUrl = "https://a.example.com/list"),
                hops = emptyList(),
                credentials = null,
            )

            val outcome = subscribeBundle.useCase("https://a.example.com/list", emptySet())

            assertEquals(
                SubscribeError.Fetch(AddPodcastError.UnsupportedListFeed),
                assertIs<Outcome.Failure<SubscribeError>>(outcome).error,
            )
            assertTrue(db.podcastDao().ungroupedIds().isEmpty())
            assertNull(
                db.podcastDao().byFeedKey(UrlNormalizer.forIdentity("https://a.example.com/list")!!),
            )
            assertEquals(0, scheduler.rescheduleCount)
        }

    // --- Description-size atomicity (02 episode_description bound, 03 Subscribe transaction) ----------

    /**
     * A producer whose `ParseLimits` admits a description past the codec's 2 MiB UTF-8 bound
     * (02 episode_description): `prepare`'s encode rejects it, aborting the subscribe
     * transaction — no podcast, episode, description, alias or membership row may survive,
     * and existing user state stays byte-identical. The feed is parsed by the real
     * `XmlPullFeedParser` from actual XML input under the producer's widened text limit.
     */
    @Test
    fun anOverBoundEpisodeDescriptionRollsBackTheWholeSubscribe() =
        runTest {
            // Existing user state the failed subscribe must leave untouched: a podcast with one
            // episode carrying a readable description and played/favourite state.
            val keepId = seedPodcast(db, feedUrl = "https://b.example.com/keep")
            newIngestor(db, clock).ingest(
                dueFeedOf(db, keepId),
                parsedFeed(
                    items =
                        listOf(parsedEpisode(0, guid = "keep-1", descriptionHtml = "seeded notes")),
                ),
                IngestContext(
                    mode = IngestMode.INITIAL,
                    partial = false,
                    fetch = fetchMeta(finalUrl = "https://b.example.com/keep"),
                ),
            )
            val keepEpisode = assertNotNull(db.episodeDao().byIdentityKey(keepId, "g:keep-1"))
            db
                .episodeStateDao()
                .upsert(
                    EpisodeStateEntity(
                        episodeId = keepEpisode.id,
                        playedAt = NOW - DAY,
                        isFavorite = true,
                        updatedAt = NOW - DAY,
                    ),
                )
            val podcastBefore = db.podcastDao().byId(keepId)
            val episodeBefore = db.episodeDao().byId(keepEpisode.id)
            val stateBefore = db.episodeStateDao().byEpisode(keepEpisode.id)
            val groupId =
                db
                    .groupDao()
                    .insert(
                        PodcastGroupEntity(
                            uuid = "g-atomicity",
                            name = "Atomicity",
                            nameKey = "atomicity",
                            orderKey = "a",
                            createdAt = NOW,
                            updatedAt = NOW,
                        ),
                    )

            // OVER_BOUND_CHARS CJK chars sit exactly at the producer's parser cap — legally
            // collected without truncation — but encode to 2359296 UTF-8 bytes, over the bound.
            val url = "https://a.example.com/oversized"
            val oversized = CJK.repeat(OVER_BOUND_CHARS)
            val parsed =
                parseWithProducerLimits(
                    rssBody(
                        items =
                            arrayOf(
                                rssItem("first"),
                                rssItem("big", extra = "<description>$oversized</description>"),
                            ),
                    ),
                    url,
                )
            assertEquals(2, parsed.items.size)
            assertEquals(oversized, parsed.items[1].descriptionHtml)
            assertTrue(parsed.warnings.none { it.code == WarningCode.TEXT_TRUNCATED })
            resolverBundle.cache.put(
                previewId = url,
                inputUrl = url,
                feed = parsed,
                meta = fetchMeta(finalUrl = url),
                hops = emptyList(),
                credentials = null,
            )

            val outcome = subscribeBundle.useCase(url, setOf(groupId))

            assertEquals(SubscribeError.Storage, assertIs<Outcome.Failure<SubscribeError>>(outcome).error)
            // The whole transaction rolled back: nothing it wrote survives.
            assertNull(db.podcastDao().byFeedKey(UrlNormalizer.forIdentity(url)!!))
            assertNull(db.podcastDao().aliasOwner(UrlNormalizer.forIdentity(url)!!))
            assertTrue(db.groupDao().membersOf(groupId).isEmpty())
            assertEquals(1, db.rowCount("episode"))
            assertEquals(1, db.rowCount("episode_description"))
            // Existing user state is untouched and no post-commit side effect ran.
            assertEquals(podcastBefore, db.podcastDao().byId(keepId))
            assertEquals(episodeBefore, db.episodeDao().byId(keepEpisode.id))
            assertEquals(stateBefore, db.episodeStateDao().byEpisode(keepEpisode.id))
            assertEquals(
                "seeded notes",
                EpisodeDescriptionCodec.decode(
                    assertNotNull(db.episodeDao().observeDescription(keepEpisode.id).first()),
                ),
            )
            assertEquals(0, scheduler.rescheduleCount)
            assertTrue(scheduler.nowRequests.isEmpty())
            assertSyncInert()
        }

    /** A multibyte description sized exactly to the codec bound commits and round-trips. */
    @Test
    fun aDescriptionAtTheCodecBoundCommitsAndDecodes() =
        runTest {
            val url = "https://a.example.com/exact-bound"
            // AT_BOUND_CJK_CHARS x 3 UTF-8 bytes + 2 ASCII bytes lands exactly on the bound.
            val atBound = CJK.repeat(AT_BOUND_CJK_CHARS) + "ab"
            assertEquals(CODEC_BOUND_BYTES, atBound.encodeToByteArray().size)
            val parsed =
                parseWithProducerLimits(
                    rssBody(
                        items = arrayOf(rssItem("edge", extra = "<description>$atBound</description>")),
                    ),
                    url,
                )
            assertEquals(atBound, parsed.items.single().descriptionHtml)
            assertTrue(parsed.warnings.none { it.code == WarningCode.TEXT_TRUNCATED })
            resolverBundle.cache.put(
                previewId = url,
                inputUrl = url,
                feed = parsed,
                meta = fetchMeta(finalUrl = url),
                hops = emptyList(),
                credentials = null,
            )

            val id = assertIs<Outcome.Success<Long>>(subscribeBundle.useCase(url, emptySet())).value

            assertEquals(1, db.podcastDao().episodeCount(id))
            val episode = assertNotNull(db.episodeDao().byIdentityKey(id, "g:edge"))
            val blob = assertNotNull(db.episodeDao().observeDescription(episode.id).first())
            assertEquals(atBound, EpisodeDescriptionCodec.decode(blob))
        }

    /** Parses [body] through the real parser under a producer's widened text limit. */
    private fun parseWithProducerLimits(
        body: String,
        baseUrl: String,
    ): ParsedFeed {
        val parser = XmlPullFeedParser.discovered(ParseLimits(maxTextChars = PRODUCER_MAX_TEXT_CHARS))
        return assertIs<ParseResult.Ok>(
            parser.parse({ Buffer().writeUtf8(body) }, httpCharset = null, baseUrl = baseUrl),
        ).feed
    }

    /** A raw `COUNT(*)` on the reader pool — the assertion targets the table, not a DAO view. */
    private suspend fun NeutrodyneDatabase.rowCount(table: String): Long =
        useReaderConnection { conn ->
            conn.usePrepared("SELECT COUNT(*) FROM $table") { stmt ->
                check(stmt.step()) { "COUNT(*) returned no row" }
                stmt.getLong(0)
            }
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
                db
                    .groupDao()
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
        const val DAY = 86_400_000L

        /** The producer-side text limit that admits the oversized description (03 Limits). */
        const val PRODUCER_MAX_TEXT_CHARS = 768 * 1024

        /** Exactly at the parser cap; 786432 x 3 UTF-8 bytes = 2359296 — over the codec bound. */
        const val OVER_BOUND_CHARS = 786_432

        /** 699050 x 3 UTF-8 bytes + 2 ASCII bytes = 2097152 — exactly the codec bound. */
        const val AT_BOUND_CJK_CHARS = 699_050

        /** The codec's documented decoded-size bound (02 episode_description). */
        const val CODEC_BOUND_BYTES = 2 * 1024 * 1024

        /** '界' (U+754C): three UTF-8 bytes per character. */
        const val CJK = "界"
    }
}
