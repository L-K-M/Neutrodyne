// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.add.PreviewEntry
import ch.lkmc.neutrodyne.core.data.add.PreviewEntryResolver
import ch.lkmc.neutrodyne.core.data.credentials.DesktopSecretStore
import ch.lkmc.neutrodyne.core.data.credentials.IdentitySecretProtection
import ch.lkmc.neutrodyne.core.data.ingest.FetchMeta
import ch.lkmc.neutrodyne.core.data.ingest.IngestContext
import ch.lkmc.neutrodyne.core.data.ingest.IngestMode
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshRequest
import ch.lkmc.neutrodyne.core.data.refresh.RefreshScheduler
import ch.lkmc.neutrodyne.core.data.repo.PodcastMerger
import ch.lkmc.neutrodyne.core.data.repo.PodcastRepositoryImpl
import ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.AddResolution
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupShowNotesSanitizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import org.junit.Rule
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Explicit moves through the real fetch/parser and Room bundled driver, without a UI caller. */
class EditFeedUrlTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server
    private val clock = TestClock(NOW)
    private val db = newDb(clock)
    private val root =
        kotlin.io.path
            .createTempDirectory("nd-edit-feed")
            .toFile()
    private val resolver = newResolver(root, db, clock)
    private val scheduler = FakeRefreshScheduler()
    private val adapter = stubAdapter()

    // One shared coordinator, and a real store: `podcast.credentialId` references real
    // `credential` rows, and refresh's origin lookup only exists against the persisted map.
    private val coordinator = FakeCredentialCommitCoordinator()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store =
        DesktopSecretStore(
            db = db,
            appDirs = appDirsFor(root),
            coordinator = coordinator,
            clock = clock,
            protection = IdentitySecretProtection,
            scope = storeScope,
        )
    private val engine =
        newRefresher(
            db,
            mapOf(SourceType.RSS to adapter),
            clock,
            scheduler = scheduler,
            secrets = store,
            coordinator = coordinator,
        )
    private val repository =
        PodcastRepositoryImpl(
            db,
            engine,
            scheduler,
            JsoupShowNotesSanitizer(),
            clock,
            PodcastMerger(db, coordinator, clock),
            resolver.resolver,
            store,
            coordinator,
            unusedProbeFetcher,
            scratchTempFiles("edit-feed-url"),
        )

    @AfterTest
    fun tearDown() {
        storeScope.cancel()
        resolver.close()
        db.close()
        root.deleteRecursively()
    }

    private fun url(path: String = "/new") = server.url(path).toString()

    private fun origin(url: String) = requireNotNull(UrlNormalizer.origin(url))

    private fun request(id: Long) =
        RefreshRequest(
            scope = RefreshScope.Podcasts(listOf(id)),
            force = true,
            pagesOnly = false,
            origin = RefreshOrigin.MANUAL,
        )

    private fun key(url: String) = requireNotNull(UrlNormalizer.forIdentity(url))

    private fun body() =
        rssBody(
            items =
                arrayOf(
                    rssItem(
                        "episode",
                        extra = "<pubDate>Wed, 14 Oct 2026 08:00:00 GMT</pubDate>",
                    ),
                ),
        )

    private suspend fun seedWithState(): Long {
        val id = seedPodcast(db, url("/old"), subscribedAt = NOW - DAY)
        newIngestor(db, clock).ingest(
            dueFeedOf(db, id),
            parsedFeed(
                items =
                    listOf(
                        parsedEpisode(0, guid = "episode", enclosureUrl = "https://cdn.example.com/episode.mp3"),
                    ),
            ),
            IngestContext(IngestMode.INITIAL, partial = false, fetch = fetchMeta(url("/old"))),
        )
        val episodeId =
            db
                .ingestDao()
                .existing(id)
                .single()
                .id
        db.episodeStateDao().upsert(episodeStateEntity(episodeId, playedAt = NOW - 1) { copy(isFavorite = true) })
        return id
    }

    @Test
    fun invalidInputDoesNotFetchOrChangeTheSubscription() =
        runTest {
            val id = seedWithState()
            val before = db.podcastDao().byId(id)
            assertEquals(
                Outcome.Failure(AddPodcastError.InvalidUrl),
                repository.editFeedUrl(id, "ftp://example.com/feed"),
            )
            assertEquals(
                Outcome.Failure(AddPodcastError.NotAUrl("a podcast")),
                repository.editFeedUrl(id, "a podcast"),
            )
            assertEquals(before, db.podcastDao().byId(id))
            assertEquals(0, server.requestCount)
        }

    @Test
    fun youTubeIsRejectedBeforeFetching() =
        runTest {
            val id = seedPodcast(db, url("/old"))
            val inputs =
                listOf(
                    "youtu.be/video",
                    "https://www.youtube.com/feeds/videos.xml?channel_id=UCfoo",
                    "neutrodyne://subscribe?url=https%3A%2F%2Fyoutube.com%2Ffeed",
                )
            for (input in inputs) {
                assertEquals(Outcome.Failure(AddPodcastError.InvalidUrl), repository.editFeedUrl(id, input))
            }
            assertEquals(0, server.requestCount)
        }

    @Test
    fun aYouTubeUrlReachedByTheProbeCannotBeStoredAsRss() =
        runTest {
            val id = seedPodcast(db, url("/old"))
            val before = db.podcastDao().byId(id)
            server.enqueue(mockResponse(body = body()))
            val entry = assertIs<Outcome.Success<PreviewEntry>>(resolver.resolver.probe(url())).value
            // Model a terminal redirect to YouTube without making an external network request.
            val repo =
                repositoryWithMeta(
                    entry,
                    entry.meta.copy(finalUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=UCfoo"),
                )
            assertEquals(Outcome.Failure(AddPodcastError.InvalidUrl), repo.editFeedUrl(id, url()))
            assertEquals(before, db.podcastDao().byId(id))
        }

    private fun repositoryWithMeta(
        entry: PreviewEntry,
        meta: FetchMeta,
        credentials: BasicCredentials? = entry.credentials,
    ): PodcastRepositoryImpl {
        val probe =
            object : PreviewEntryResolver by resolver.resolver {
                override suspend fun probe(input: String): Outcome<PreviewEntry, AddPodcastError> =
                    Outcome.Success(
                        PreviewEntry(
                            entry.previewId,
                            entry.inputUrl,
                            entry.feed,
                            meta,
                            entry.hops,
                            credentials,
                            entry.createdAt,
                        ),
                    )
            }
        return PodcastRepositoryImpl(
            db,
            engine,
            scheduler,
            JsoupShowNotesSanitizer(),
            clock,
            PodcastMerger(db, coordinator, clock),
            probe,
            store,
            coordinator,
            unusedProbeFetcher,
            scratchTempFiles("edit-feed-url"),
        )
    }

    @Test
    fun anEmptyRedirectRequiresAValidRequestedIdentity() =
        runTest {
            val id = seedWithState()
            val before = db.podcastDao().byId(id)
            val episodes = db.ingestDao().existing(id)
            server.enqueue(mockResponse(body = rssBody(title = "Empty target")))
            val entry = assertIs<Outcome.Success<PreviewEntry>>(resolver.resolver.probe(url())).value
            val input = "http://0177.1/feed"
            assertNull(UrlNormalizer.forIdentity(input))
            // A leading permanent redirect validates its target in the resolver, but an empty
            // body cannot adopt it. The remaining requested identity still needs validation.
            val repo = repositoryWithMeta(entry, entry.meta.copy(requestedUrl = input, permanentUrl = url()))
            assertEquals(Outcome.Failure(AddPodcastError.InvalidUrl), repo.editFeedUrl(id, input))
            assertEquals(before, db.podcastDao().byId(id))
            assertEquals(episodes, db.ingestDao().existing(id))
            assertTrue(db.podcastDao().aliases(id).isEmpty())
        }

    @Test
    fun permanentRedirectUserinfoIsNeverPersisted() =
        runTest {
            val id = seedPodcast(db, url("/old"))
            val target = "http://u:p@${server.hostName}:${server.port}/new"
            server.enqueue(mockResponse(301, "", "Location" to target))
            server.enqueue(mockResponse(body = body()))
            assertEquals(Outcome.Success(Unit), repository.editFeedUrl(id, url("/typed")))
            val stored = assertNotNull(db.podcastDao().byId(id))
            assertEquals(url(), stored.feedUrl)
            assertEquals(key(url()), stored.feedKey)
            assertNull(stored.credentialId)
            assertTrue(db.podcastDao().aliases(id).none { it.url.contains('@') })
        }

    @Test
    fun htmlWithoutAutodiscoveryIsRejected() =
        runTest {
            val id = seedWithState()
            val before = db.podcastDao().byId(id)
            server.enqueue(mockResponse(body = "<!DOCTYPE html><html><body>A page</body></html>"))
            assertEquals(Outcome.Failure(AddPodcastError.NotAFeed), repository.editFeedUrl(id, url()))
            assertEquals(before, db.podcastDao().byId(id))
        }

    @Test
    fun htmlWithAFeedLinkIsRejectedWithoutAutodiscovery() =
        runTest {
            val id = seedPodcast(db, url("/old"))
            server.enqueue(
                mockResponse(
                    body =
                        """
                        <!DOCTYPE html><html><head>
                        <link rel="alternate" type="application/rss+xml" href="/feed.xml">
                        </head></html>
                        """.trimIndent(),
                ),
            )
            assertEquals(Outcome.Failure(AddPodcastError.NotAFeed), repository.editFeedUrl(id, url()))
            assertEquals(1, server.requestCount)
            assertEquals(url("/old"), db.podcastDao().byId(id)!!.feedUrl)
        }

    @Test
    fun anEmptyFeedIsAcceptedAndLeavesExistingEpisodesIntact() =
        runTest {
            val id = seedWithState()
            val episodes = db.ingestDao().existing(id)
            server.enqueue(mockResponse(body = rssBody(title = "An empty show")))
            assertEquals(Outcome.Success(Unit), repository.editFeedUrl(id, url()))
            assertEquals(url(), db.podcastDao().byId(id)!!.feedUrl)
            assertEquals("An empty show", db.podcastDao().byId(id)!!.title)
            assertEquals(episodes, db.ingestDao().existing(id))
            assertEquals(1, scheduler.rescheduleCount)
        }

    @Test
    fun feedsWithItemsButNoMediaAndEmptyListFeedsAreRejected() =
        runTest {
            val id = seedWithState()
            val before = db.podcastDao().byId(id)
            server.enqueue(mockResponse(body = rssBody(items = arrayOf("<item><title>A post</title></item>"))))
            assertEquals(Outcome.Failure(AddPodcastError.NoMedia), repository.editFeedUrl(id, url()))
            server.enqueue(
                mockResponse(
                    body =
                        """
                        <rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0">
                        <channel><title>List</title><podcast:medium>podcastL</podcast:medium></channel></rss>
                        """.trimIndent(),
                ),
            )
            assertEquals(Outcome.Failure(AddPodcastError.UnsupportedListFeed), repository.editFeedUrl(id, url()))
            assertEquals(before, db.podcastDao().byId(id))
        }

    @Test
    fun a404LeavesAllSubscriptionDataUntouched() =
        runTest {
            assertFailedProbePreservesState(mockResponse(404, "Missing"), AddPodcastError.Http(404))
        }

    @Test
    fun aTimeoutLeavesAllSubscriptionDataUntouched() =
        runTest {
            assertFailedProbePreservesState(
                MockResponse
                    .Builder()
                    .headersDelay(31, TimeUnit.SECONDS)
                    .body(body())
                    .build(),
                AddPodcastError.Network(NetError.Timeout),
            )
        }

    private suspend fun assertFailedProbePreservesState(
        response: MockResponse,
        error: AddPodcastError,
    ) {
        val id = seedWithState()
        db.podcastDao().insertAlias(
            PodcastUrlAliasEntity(key(url("/previous")), id, AliasReason.REDIRECT, NOW - DAY),
        )
        val before = db.podcastDao().byId(id)
        val aliases = db.podcastDao().aliases(id)
        val episodes = db.ingestDao().existing(id)
        val state = db.episodeStateDao().byEpisode(episodes.single().id)
        server.enqueue(response)
        assertEquals(Outcome.Failure(error), repository.editFeedUrl(id, url()))
        assertEquals(before, db.podcastDao().byId(id))
        assertEquals(aliases, db.podcastDao().aliases(id))
        assertEquals(episodes, db.ingestDao().existing(id))
        assertEquals(state, db.episodeStateDao().byEpisode(episodes.single().id))
        assertEquals(0, scheduler.rescheduleCount)
    }

    @Test
    fun successMovesResetsBlocksAndPreservesEpisodeUserState() =
        runTest {
            val id = seedWithState()
            val episodeId =
                db
                    .ingestDao()
                    .existing(id)
                    .single()
                    .id
            val state = db.episodeStateDao().byEpisode(episodeId)
            db.useWriterConnection {
                it.execSql(
                    """
                    UPDATE podcast SET etag = 'old', lastModified = 'old-date', contentSha256 = 'old-sha',
                    gone = 1, needsCredentials = 1, failureCount = 4,
                    lastErrorKind = 'HTTP_CLIENT', lastErrorDetail = 'old error' WHERE id = $id
                    """.trimIndent(),
                )
            }
            server.enqueue(mockResponse(200, body(), "ETag" to "new", "Last-Modified" to "new-date"))
            assertEquals(Outcome.Success(Unit), repository.editFeedUrl(id, "feed:${url()}"))
            val stored = assertNotNull(db.podcastDao().byId(id))
            assertEquals(url(), stored.feedUrl)
            assertEquals(key(url()), stored.feedKey)
            val alias = db.podcastDao().aliases(id).single()
            assertEquals(key(url("/old")), alias.url)
            assertEquals(AliasReason.SUBSCRIBE_INPUT, alias.reason)
            assertNull(stored.etag)
            assertNull(stored.lastModified)
            assertNull(stored.contentSha256)
            assertFalse(stored.gone)
            assertFalse(stored.needsCredentials)
            assertEquals(0, stored.failureCount)
            assertNull(stored.lastErrorKind)
            assertNull(stored.lastErrorDetail)
            assertEquals(state, db.episodeStateDao().byEpisode(episodeId))
            assertEquals(
                episodeId,
                db
                    .ingestDao()
                    .existing(id)
                    .single()
                    .id,
            )
            val request = server.takeRequest()
            assertNull(request.headers["If-None-Match"])
            assertNull(request.headers["If-Modified-Since"])
            assertEquals(1, scheduler.rescheduleCount)
        }

    @Test
    fun pendingSubscriptionsUseInitialAndActiveSubscriptionsUseRefresh() =
        runTest {
            for (pending in listOf(true, false)) {
                val id =
                    seedPodcast(
                        db,
                        url("/old-$pending"),
                        subscribedAt = NOW - DAY,
                        initialFetch = pending,
                        status = if (pending) PodcastStatus.PENDING_FIRST_FETCH else PodcastStatus.ACTIVE,
                    )
                server.enqueue(mockResponse(body = body()))
                assertEquals(Outcome.Success(Unit), repository.editFeedUrl(id, url("/new-$pending")))
                val stored = assertNotNull(db.podcastDao().byId(id))
                assertEquals(PodcastStatus.ACTIVE, stored.status)
                assertFalse(stored.initialFetch)
                val expectedNewIds = if (pending) emptyList() else db.ingestDao().existing(id).map { it.id }
                assertEquals(expectedNewIds, adapter.afterIngestCalls.last().third)
            }
        }

    @Test
    fun collisionsMergeIntoTheKeyOrAliasOwnerAndIngestThere() =
        runTest {
            for (alias in listOf(false, true)) {
                val loser = seedWithState()
                val loserEpisode =
                    db
                        .ingestDao()
                        .existing(loser)
                        .single()
                        .id
                val target = url("/collision-$alias")
                val winner = seedPodcast(db, if (alias) url("/winner") else target)
                if (alias) {
                    db.podcastDao().insertAlias(PodcastUrlAliasEntity(key(target), winner, AliasReason.REDIRECT, NOW))
                }
                server.enqueue(mockResponse(body = body()))
                assertEquals(Outcome.Success(Unit), repository.editFeedUrl(loser, target))
                assertNull(db.podcastDao().byId(loser))
                assertNotNull(db.podcastDao().byId(winner))
                assertEquals(if (alias) url("/winner") else target, db.podcastDao().byId(winner)!!.feedUrl)
                assertEquals(winner, db.podcastDao().aliasOwner(key(url("/old"))))
                assertEquals(
                    loserEpisode,
                    db
                        .ingestDao()
                        .existing(winner)
                        .single()
                        .id,
                )
                assertTrue(db.episodeStateDao().byEpisode(loserEpisode)!!.isFavorite)
                assertEquals(winner, adapter.afterIngestCalls.last().first)
                // Clear survivor's old key alias so the next independent scenario can seed /old.
                db.podcastDao().deleteAlias(winner, key(url("/old")))
            }
        }

    @Test
    fun aNonRssOrMissingPodcastIsRejectedWithoutFetching() =
        runTest {
            val id = seedPodcast(db, url("/youtube")) { copy(sourceType = SourceType.YOUTUBE_CHANNEL) }
            assertEquals(Outcome.Failure(AddPodcastError.InvalidUrl), repository.editFeedUrl(id, url()))
            assertEquals(Outcome.Failure(AddPodcastError.InvalidUrl), repository.editFeedUrl(Long.MAX_VALUE, url()))
            assertEquals(0, server.requestCount)
        }

    /**
     * 03 Edit URL + Basic auth: the request URL's userinfo probes the feed, then persists under
     * the ORIGINAL request origin inside the credential commit — the stored feed URL never
     * carries it, and the next refresh answers the fetcher's lookup through `credentialId`.
     */
    @Test
    fun userinfoIsPersistedUnderItsRequestOriginAndReusedByRefresh() =
        runTest {
            val id = seedPodcast(db, url("/old"))
            server.enqueue(mockResponse(body = body()))
            val input = "http://u:p%40ss@${server.hostName}:${server.port}/new"
            assertEquals(Outcome.Success(Unit), repository.editFeedUrl(id, input))
            assertEquals("Basic dTpwQHNz", server.takeRequest().headers["Authorization"])
            val stored = assertNotNull(db.podcastDao().byId(id))
            assertEquals(url(), stored.feedUrl)
            assertEquals(key(url()), stored.feedKey)
            assertNull(resolver.cache.findByUrl(url()))
            assertTrue(db.podcastDao().aliases(id).none { it.url.contains('@') })

            val credential =
                db
                    .credentialDao()
                    .byOrigin(origin(url()))
                    .single()
            assertEquals(credential.id, stored.credentialId)

            // The refresh run's fetcher resolves the credential by the feed's request origin.
            server.enqueue(mockResponse(body = rssBody(title = "Refreshed")))
            newRssAdapter(root, clock, credentials = store).use { bundle ->
                val refreshEngine =
                    newRefresher(
                        db,
                        mapOf(SourceType.RSS to bundle.adapter),
                        clock,
                        tempFiles = bundle.tempFiles,
                        scheduler = scheduler,
                        secrets = store,
                        coordinator = coordinator,
                    )
                requireNotNull(refreshEngine.run(request(id)).outcomes[id])
            }
            assertEquals("Basic dTpwQHNz", server.takeRequest().headers["Authorization"])
        }

    @Test
    fun aFailedProbeStoresNoCredentials() =
        runTest {
            val id = seedPodcast(db, url("/old"))
            server.enqueue(mockResponse(404, "Missing"))
            val input = "http://u:p@${server.hostName}:${server.port}/new"
            assertEquals(
                Outcome.Failure(AddPodcastError.Http(404)),
                repository.editFeedUrl(id, input),
            )
            assertTrue(db.credentialDao().byOrigin(origin(url())).isEmpty())
            assertNull(db.podcastDao().byId(id)!!.credentialId)
        }

    /**
     * The credential keeps the ORIGINAL request's origin even when a permanent redirect moves
     * the subscription elsewhere — it must never be relabelled to the target (03).
     */
    @Test
    fun aRedirectTargetNeverInheritsTheRequestCredentials() =
        runTest {
            val id = seedPodcast(db, url("/old"))
            server.enqueue(mockResponse(body = body()))
            val entry = assertIs<Outcome.Success<PreviewEntry>>(resolver.resolver.probe(url())).value
            server.takeRequest()
            val repo =
                repositoryWithMeta(
                    entry,
                    entry.meta.copy(
                        requestedUrl = "https://request.example.com/feed",
                        permanentUrl = url("/moved"),
                    ),
                    credentials = BasicCredentials("u", "p"),
                )
            assertEquals(Outcome.Success(Unit), repo.editFeedUrl(id, url("/typed")))
            val stored = assertNotNull(db.podcastDao().byId(id))
            assertEquals(url("/moved"), stored.feedUrl)
            val credential =
                db
                    .credentialDao()
                    .byOrigin("https://request.example.com")
                    .single()
            assertEquals(credential.id, stored.credentialId)
            assertTrue(db.credentialDao().byOrigin(origin(url("/moved"))).isEmpty())
        }

    @Test
    fun permanentRedirectsAreAdoptedOnlyWithAcceptedMedia() =
        runTest {
            for (empty in listOf(true, false)) {
                val id = seedPodcast(db, url("/old-$empty"))
                val input = url("/typed-$empty")
                val target = url("/permanent-$empty")
                server.enqueue(mockResponse(301, "", "Location" to target))
                server.enqueue(mockResponse(body = if (empty) rssBody() else body()))
                assertEquals(Outcome.Success(Unit), repository.editFeedUrl(id, input))
                val stored = assertNotNull(db.podcastDao().byId(id))
                assertEquals(if (empty) input else target, stored.feedUrl)
                assertEquals(key(stored.feedUrl), stored.feedKey)
                assertEquals(
                    AliasReason.SUBSCRIBE_INPUT,
                    db
                        .podcastDao()
                        .aliases(id)
                        .single()
                        .reason,
                )
            }
        }

    @Test
    fun temporaryRedirectsKeepTheTypedUrlAsTheSubscriptionIdentity() =
        runTest {
            val id = seedPodcast(db, url("/old"))
            server.enqueue(mockResponse(302, "", "Location" to "/temporary"))
            server.enqueue(mockResponse(body = body()))
            assertEquals(Outcome.Success(Unit), repository.editFeedUrl(id, url()))
            assertEquals(url(), db.podcastDao().byId(id)!!.feedUrl)
            assertEquals(key(url()), db.podcastDao().byId(id)!!.feedKey)
        }

    @Test
    fun editingTheSameUrlIgnoresThePreviewCacheAndValidators() =
        runTest {
            val id = seedPodcast(db, url(), etag = "old")
            server.enqueue(mockResponse(body = rssBody(title = "Cached")))
            assertIs<AddResolution.Feed>(resolver.resolver.resolve(url()))
            server.takeRequest()
            server.enqueue(mockResponse(body = rssBody(title = "Probed")))
            assertEquals(Outcome.Success(Unit), repository.editFeedUrl(id, url()))
            assertEquals("Probed", db.podcastDao().byId(id)!!.title)
            assertNull(server.takeRequest().headers["If-None-Match"])
            assertTrue(db.podcastDao().aliases(id).isEmpty())
        }

    @Test
    fun moveAndIngestRollBackTogetherOnDatabaseFailure() =
        runTest {
            val id = seedWithState()
            val before = db.podcastDao().byId(id)
            val episodes = db.ingestDao().existing(id)
            db.useWriterConnection {
                it.execSql(
                    """
                    CREATE TRIGGER fail_edit BEFORE UPDATE OF title ON podcast
                    BEGIN SELECT RAISE(ABORT, 'edit ingest'); END
                    """.trimIndent(),
                )
            }
            server.enqueue(mockResponse(body = body()))
            // The userinfo `put` commits before the write transaction; the abort rolls both
            // back — no `credential` row may outlive a failed commit (03's cleanup rule).
            val input = "http://u:p@${server.hostName}:${server.port}/new"
            assertTrue(runCatching { repository.editFeedUrl(id, input) }.isFailure)
            assertEquals(before, db.podcastDao().byId(id))
            assertTrue(db.podcastDao().aliases(id).isEmpty())
            assertEquals(episodes, db.ingestDao().existing(id))
            assertTrue(db.credentialDao().byOrigin(origin(url())).isEmpty())
            assertEquals(0, scheduler.rescheduleCount)
        }

    @Test
    fun aSchedulerFailureCannotReportACommittedEditAsFailed() =
        runTest {
            val id = seedPodcast(db, url("/old"))
            val failingScheduler =
                object : RefreshScheduler by scheduler {
                    override suspend fun reschedulePeriodic() = error("scheduler unavailable")
                }
            val faultyEngine =
                newRefresher(
                    db,
                    mapOf(SourceType.RSS to adapter),
                    clock,
                    scheduler = failingScheduler,
                    secrets = store,
                    coordinator = coordinator,
                )
            val repo =
                PodcastRepositoryImpl(
                    db,
                    faultyEngine,
                    failingScheduler,
                    JsoupShowNotesSanitizer(),
                    clock,
                    PodcastMerger(db, coordinator, clock),
                    resolver.resolver,
                    store,
                    coordinator,
                    unusedProbeFetcher,
                    scratchTempFiles("edit-feed-url"),
                )
            server.enqueue(mockResponse(body = body()))
            assertEquals(Outcome.Success(Unit), repo.editFeedUrl(id, url()))
            assertEquals(url(), db.podcastDao().byId(id)!!.feedUrl)
            assertEquals(
                AliasReason.SUBSCRIBE_INPUT,
                db
                    .podcastDao()
                    .aliases(id)
                    .single()
                    .reason,
            )
            assertEquals(1, db.ingestDao().existing(id).size)
        }

    @Test
    fun concurrentEditsProbeAndCommitInOrder() =
        runTest {
            val id = seedPodcast(db, url("/old"))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first =
                async(Dispatchers.Default) {
                    engine.editFeedUrl(id) {
                        entered.complete(Unit)
                        release.await()
                        resolver.resolver.probe(url("/first"))
                    }
                }
            entered.await()
            val second = async(Dispatchers.Default) { repository.editFeedUrl(id, url("/second")) }
            server.enqueue(mockResponse(body = body()))
            server.enqueue(mockResponse(body = body()))
            release.complete(Unit)
            assertEquals(Outcome.Success(Unit), first.await())
            assertEquals(Outcome.Success(Unit), second.await())
            assertEquals(url("/second"), db.podcastDao().byId(id)!!.feedUrl)
            assertEquals("/first", withContext(Dispatchers.IO) { server.takeRequest().target })
            assertEquals("/second", withContext(Dispatchers.IO) { server.takeRequest().target })
            assertEquals(
                setOf(key(url("/old")), key(url("/first"))),
                db
                    .podcastDao()
                    .aliases(id)
                    .map { it.url }
                    .toSet(),
            )
        }

    private companion object {
        const val NOW = 1_791_964_800_000L
        const val DAY = 86_400_000L
    }
}
