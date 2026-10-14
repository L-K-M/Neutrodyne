// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.data.refresh.FeedMoveCommitter
import ch.lkmc.neutrodyne.core.data.refresh.FeedOutcome
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshRequest
import ch.lkmc.neutrodyne.core.data.refresh.RefreshScheduler
import ch.lkmc.neutrodyne.core.data.repo.PodcastMerger
import ch.lkmc.neutrodyne.core.database.PodcastEntity
import ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity
import ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import ch.lkmc.neutrodyne.feeds.parse.FeedParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import mockwebserver3.junit4.MockWebServerRule
import okio.ByteString.Companion.encodeUtf8
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

/** AC2/9: production HTTP → parse → refresh → Room, including collision survivor ingests. */
class FeedMovesTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server
    private val clock = TestClock(NOW)
    private val db = newDb(clock)
    private val root =
        kotlin.io.path
            .createTempDirectory("nd-feed-moves")
            .toFile()
    private val scheduler = FakeRefreshScheduler()
    private val body = rssBody(items = arrayOf(rssItem("episode")))

    @AfterTest
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private fun url(path: String) = server.url(path).toString()

    private fun key(url: String) = requireNotNull(UrlNormalizer.forIdentity(url))

    private suspend fun seed(
        url: String = url("/old"),
        block: PodcastEntity.() -> PodcastEntity = { this },
    ) = seedPodcast(db, url, feedKey = key(url), subscribedAt = NOW - DAY, block = block)

    private fun redirect(
        code: Int,
        target: String,
    ) = mockResponse(code, "", "Location" to target)

    private fun request(id: Long) =
        RefreshRequest(
            scope = RefreshScope.Podcasts(listOf(id)),
            force = true,
            pagesOnly = false,
            origin = RefreshOrigin.MANUAL,
        )

    private suspend fun refresh(id: Long): FeedOutcome =
        newRssAdapter(root, clock).use { bundle ->
            val engine =
                newRefresher(
                    db,
                    mapOf(SourceType.RSS to bundle.adapter),
                    clock,
                    tempFiles = bundle.tempFiles,
                    scheduler = scheduler,
                )
            requireNotNull(engine.run(request(id)).outcomes[id])
        }

    private suspend fun assertMove(
        id: Long,
        target: String,
        oldKey: String,
        reason: AliasReason,
    ) {
        val stored = assertNotNull(db.podcastDao().byId(id))
        assertEquals(target, stored.feedUrl)
        assertEquals(key(target), stored.feedKey)
        val aliases = db.podcastDao().aliases(id)
        assertEquals(listOf(oldKey), aliases.map { it.url })
        assertEquals(reason, aliases.single().reason)
        assertEquals(NOW, aliases.single().addedAt)
        assertNull(stored.etag)
        assertNull(stored.lastModified)
        assertNull(stored.contentSha256)
        assertTrue(assertNotNull(stored.nextRefreshAt) > NOW)
        assertEquals(1, db.podcastDao().episodeCount(id))
    }

    @Test
    fun leading301ChainAdoptsAndDiscardsValidators() =
        runTest {
            val original = url("/old")
            val id =
                seed(original) {
                    copy(etag = "old", lastModified = "old-date", contentSha256 = "old-sha")
                }
            server.enqueue(redirect(301, "/middle"))
            server.enqueue(redirect(301, "/new"))
            server.enqueue(mockResponse(200, body, "ETag" to "response", "Last-Modified" to "response-date"))

            assertIs<FeedOutcome.Ingested>(refresh(id))
            assertMove(id, url("/new"), key(original), AliasReason.REDIRECT)
        }

    @Test
    fun leading308ChainAdopts() =
        runTest {
            val original = url("/old")
            val id = seed(original)
            server.enqueue(redirect(308, "/middle"))
            server.enqueue(redirect(308, "/new"))
            server.enqueue(mockResponse(200, body))

            assertIs<FeedOutcome.Ingested>(refresh(id))
            assertMove(id, url("/new"), key(original), AliasReason.REDIRECT)
        }

    @Test
    fun mixedChainAdoptsOnlyTheLeadingPermanentTarget() =
        runTest {
            val original = url("/old")
            val id = seed(original)
            server.enqueue(redirect(301, "/permanent"))
            server.enqueue(redirect(302, "/temporary"))
            server.enqueue(redirect(308, "/final"))
            server.enqueue(mockResponse(200, body))

            assertIs<FeedOutcome.Ingested>(refresh(id))
            assertMove(id, url("/permanent"), key(original), AliasReason.REDIRECT)
        }

    @Test
    fun temporaryRedirectsNeverAdopt() =
        runTest {
            for (code in listOf(302, 303, 307)) {
                val original = url("/old-$code")
                val id = seed(original)
                server.enqueue(redirect(code, "/new-$code"))
                server.enqueue(mockResponse(200, body))

                assertIs<FeedOutcome.Ingested>(refresh(id))
                assertEquals(original, db.podcastDao().byId(id)?.feedUrl)
                assertTrue(db.podcastDao().aliases(id).isEmpty())
            }
        }

    @Test
    fun malformedBodyDoesNotAdopt() =
        runTest {
            val id = seed()
            server.enqueue(redirect(301, "/new"))
            server.enqueue(mockResponse(200, "<rss><channel><item>"))

            assertIs<FeedOutcome.Failed>(refresh(id))
            assertEquals(url("/old"), db.podcastDao().byId(id)?.feedUrl)
            assertTrue(db.podcastDao().aliases(id).isEmpty())
        }

    @Test
    fun emptyBodyDoesNotAdopt() =
        runTest {
            val id = seed()
            server.enqueue(redirect(301, "/new"))
            server.enqueue(mockResponse(200, rssBody()))

            assertIs<FeedOutcome.Ingested>(refresh(id))
            assertEquals(url("/old"), db.podcastDao().byId(id)?.feedUrl)
            assertTrue(db.podcastDao().aliases(id).isEmpty())
        }

    @Test
    fun noMediaBodyDoesNotAdopt() =
        runTest {
            val id = seed { copy(etag = "old", lastModified = "old-date", contentSha256 = "old-sha") }
            server.enqueue(redirect(301, "/new"))
            val noMedia = rssBody(items = arrayOf("<item><title>Blog</title></item>"))
            server.enqueue(mockResponse(200, noMedia))

            assertEquals(FeedErrorKind.NO_MEDIA, assertIs<FeedOutcome.Failed>(refresh(id)).kind)
            val stored = assertNotNull(db.podcastDao().byId(id))
            assertEquals(url("/old"), stored.feedUrl)
            assertEquals("old", stored.etag)
            assertEquals("old-sha", stored.contentSha256)
            assertTrue(db.podcastDao().aliases(id).isEmpty())
        }

    @Test
    fun redirected304ConfirmsFromOriginalUrlOnce() =
        runTest {
            val id =
                seed {
                    copy(
                        etag = "old",
                        lastModified = "old-date",
                        parserVersion = FeedParser.VERSION,
                        lastParseOk = true,
                        lastFullFetchAt = NOW,
                    )
                }
            server.enqueue(redirect(301, "/new"))
            server.enqueue(mockResponse(304, ""))
            server.enqueue(redirect(301, "/new"))
            server.enqueue(mockResponse(200, body))

            assertIs<FeedOutcome.Ingested>(refresh(id))
            assertMove(id, url("/new"), key(url("/old")), AliasReason.REDIRECT)
            val requests = (1..4).map { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            assertEquals(listOf("/old", "/new", "/old", "/new"), requests.map { it.url.encodedPath })
            assertEquals(listOf("old", "old", null, null), requests.map { it.headers["If-None-Match"] })
            val modifiedSince = requests.map { it.headers["If-Modified-Since"] }
            assertEquals(listOf("old-date", "old-date", null, null), modifiedSince)
            assertEquals(4, server.requestCount)
        }

    @Test
    fun repeated304DoesNotConfirmAgainOrAdopt() =
        runTest {
            val id =
                seed {
                    copy(etag = "old", parserVersion = FeedParser.VERSION, lastParseOk = true, lastFullFetchAt = NOW)
                }
            repeat(2) {
                server.enqueue(redirect(301, "/new"))
                server.enqueue(mockResponse(304, ""))
            }

            assertEquals(FeedErrorKind.HTTP_CLIENT, assertIs<FeedOutcome.Failed>(refresh(id)).kind)
            assertEquals(4, server.requestCount)
            assertEquals(url("/old"), db.podcastDao().byId(id)?.feedUrl)
            assertNull(db.podcastDao().byId(id)?.pendingNewFeedUrl)
            assertTrue(db.podcastDao().aliases(id).isEmpty())
        }

    @Test
    fun confirmationWithoutAcceptedMediaDoesNotAdopt() =
        runTest {
            val id =
                seed {
                    copy(etag = "old", parserVersion = FeedParser.VERSION, lastParseOk = true, lastFullFetchAt = NOW)
                }
            server.enqueue(redirect(301, "/new"))
            server.enqueue(mockResponse(304, ""))
            server.enqueue(redirect(301, "/new"))
            server.enqueue(mockResponse(200, rssBody()))

            assertIs<FeedOutcome.Ingested>(refresh(id))
            assertEquals(4, server.requestCount)
            assertEquals(url("/old"), db.podcastDao().byId(id)?.feedUrl)
        }

    @Test
    fun identicalRedirectBodyStillParsesAndAdopts() =
        runTest {
            val id = seed()
            server.enqueue(mockResponse(200, body, "ETag" to "old"))
            assertIs<FeedOutcome.Ingested>(refresh(id))
            val episodes = db.ingestDao().existing(id).map { it.id }
            server.enqueue(redirect(301, "/new"))
            server.enqueue(mockResponse(200, body, "ETag" to "new"))

            assertIs<FeedOutcome.Ingested>(refresh(id))
            assertMove(id, url("/new"), key(url("/old")), AliasReason.REDIRECT)
            assertEquals(episodes, db.ingestDao().existing(id).map { it.id })
        }

    @Test
    fun schemeOnlyUpgradeCreatesNoAlias() =
        runTest {
            // FeedFetcherTest covers TLS; isolate scheme-free identity here.
            val original = "http://feeds.example.com/show"
            val target = "https://feeds.example.com/show"
            val id = seed(original) { copy(etag = "old", contentSha256 = "old-sha") }
            val parsed =
                adapterParsed(
                    parsedFeed(items = listOf(parsedEpisode(0, guid = "episode"))),
                    meta =
                        fetchMeta(
                            requestedUrl = original,
                            finalUrl = target,
                            permanentUrl = target,
                            etag = "new",
                        ),
                )
            val adapter = stubAdapter(mutableMapOf(original to parsed))

            val engine = newRefresher(db, mapOf(SourceType.RSS to adapter), clock)
            val outcome = engine.run(request(id)).outcomes[id]
            assertIs<FeedOutcome.Ingested>(outcome)
            val stored = assertNotNull(db.podcastDao().byId(id))
            assertEquals(target, stored.feedUrl)
            assertEquals(key(original), stored.feedKey)
            assertTrue(db.podcastDao().aliases(id).isEmpty())
            assertNull(stored.etag)
            assertNull(stored.contentSha256)
        }

    @Test
    fun redirectBackToOwnAliasDeletesItBeforePromotingTheKey() =
        runTest {
            val original = url("/old")
            val target = url("/new")
            val id = seed(original)
            db.podcastDao().insertAlias(
                PodcastUrlAliasEntity(key(target), id, AliasReason.REDIRECT, NOW - DAY),
            )
            server.enqueue(redirect(301, "/new"))
            server.enqueue(mockResponse(200, body))

            assertIs<FeedOutcome.Ingested>(refresh(id))
            assertMove(id, target, key(original), AliasReason.REDIRECT)
            server.enqueue(redirect(301, "/old"))
            server.enqueue(mockResponse(200, body))
            assertIs<FeedOutcome.Ingested>(refresh(id))
            assertMove(id, original, key(target), AliasReason.REDIRECT)
        }

    @Test
    fun renormalisedKeyMovesOnParsedSuccess() =
        runTest {
            val id = seed { copy(feedKey = "legacy-key", etag = "old", contentSha256 = "old-sha") }
            server.enqueue(mockResponse(200, body, "ETag" to "new"))

            assertIs<FeedOutcome.Ingested>(refresh(id))
            assertMove(id, url("/old"), "legacy-key", AliasReason.RENORMALISED)
        }

    @Test
    fun renormalisedKeyMovesOnNoMediaSuccessScheduling() =
        runTest {
            val id = seed { copy(feedKey = "legacy-key", etag = "old", contentSha256 = "old-sha") }
            server.enqueue(redirect(301, "/new"))
            val noMedia = rssBody(items = arrayOf("<item><title>Blog</title></item>"))
            server.enqueue(mockResponse(200, noMedia))

            assertEquals(FeedErrorKind.NO_MEDIA, assertIs<FeedOutcome.Failed>(refresh(id)).kind)
            val stored = assertNotNull(db.podcastDao().byId(id))
            assertEquals(url("/old"), stored.feedUrl)
            assertEquals(key(url("/old")), stored.feedKey)
            assertEquals(
                AliasReason.RENORMALISED,
                db
                    .podcastDao()
                    .aliases(id)
                    .single()
                    .reason,
            )
            assertNull(stored.etag)
            assertNull(stored.contentSha256)
            assertEquals(NOW, stored.lastSuccessAt)
            assertEquals(0, db.podcastDao().episodeCount(id))
        }

    @Test
    fun renormalisedKeyMovesOn304Success() =
        runTest {
            val id =
                seed {
                    copy(
                        feedKey = "legacy-key",
                        etag = "old",
                        lastModified = "old-date",
                        contentSha256 = "old-sha",
                        parserVersion = FeedParser.VERSION,
                        lastParseOk = true,
                        lastFullFetchAt = NOW,
                    )
                }
            server.enqueue(mockResponse(304, "", "ETag" to "new"))

            assertEquals(FeedOutcome.NotModified, refresh(id))
            val stored = assertNotNull(db.podcastDao().byId(id))
            assertEquals(key(url("/old")), stored.feedKey)
            assertEquals(
                AliasReason.RENORMALISED,
                db
                    .podcastDao()
                    .aliases(id)
                    .single()
                    .reason,
            )
            assertNull(stored.etag)
            assertNull(stored.lastModified)
            assertNull(stored.contentSha256)
        }

    @Test
    fun renormalisedKeyMovesOnUnchangedSuccess() =
        runTest {
            val id = seed()
            server.enqueue(mockResponse(200, body))
            refresh(id)
            db.useWriterConnection { writer ->
                writer.usePrepared("UPDATE podcast SET feedKey = 'legacy-key' WHERE id = ?") {
                    it.bindLong(1, id)
                    it.step()
                }
            }
            server.enqueue(mockResponse(200, body, "ETag" to "new"))

            assertEquals(FeedOutcome.Unchanged, refresh(id))
            assertMove(id, url("/old"), "legacy-key", AliasReason.RENORMALISED)
        }

    private suspend fun collision(
        alias: Boolean,
        pending: Boolean,
    ) {
        val loser = seed()
        val winnerUrl = url(if (alias) "/winner" else "/target")
        val winner =
            seed(winnerUrl) {
                copy(
                    feedKey = if (alias) key(url("/winner")) else key(url("/target")),
                    status = if (pending) PodcastStatus.PENDING_FIRST_FETCH else PodcastStatus.ACTIVE,
                    initialFetch = pending,
                    etag = "winner-etag",
                    lastModified = "winner-date",
                    contentSha256 = "winner-sha",
                )
            }
        if (alias) {
            db.podcastDao().insertAlias(
                PodcastUrlAliasEntity(key(url("/target")), winner, AliasReason.REDIRECT, NOW),
            )
        }
        server.enqueue(redirect(301, "/target"))
        server.enqueue(mockResponse(200, body, "ETag" to "loser-response"))

        assertEquals(FeedOutcome.Merged(winner), refresh(loser))
        assertNull(db.podcastDao().byId(loser))
        val survivor = assertNotNull(db.podcastDao().byId(winner))
        assertEquals(winnerUrl, survivor.feedUrl)
        assertEquals(1, db.podcastDao().episodeCount(winner))
        assertEquals(PodcastStatus.ACTIVE, survivor.status)
        assertFalse(survivor.initialFetch)
        assertNull(survivor.etag)
        assertNull(survivor.lastModified)
        assertNull(survivor.contentSha256)
        assertTrue(assertNotNull(survivor.nextRefreshAt) > NOW)
        assertEquals(1, scheduler.rescheduleCount)
        val aliases = db.podcastDao().aliases(winner)
        assertTrue(aliases.any { it.url == key(url("/old")) && it.reason == AliasReason.MERGE })
        assertFalse(db.podcastDao().aliases(winner).any { it.url == survivor.feedKey })
        val winningEpisode =
            assertNotNull(
                db.episodeDao().byId(
                    db
                        .ingestDao()
                        .existing(winner)
                        .single()
                        .id,
                ),
            )
        assertEquals(!pending, winningEpisode.isNew)
    }

    @Test
    fun primaryKeyCollisionMergesIntoActiveOwnerAndIngests() =
        runTest {
            collision(alias = false, pending = false)
        }

    @Test
    fun primaryKeyCollisionUsesPendingSurvivorMode() =
        runTest {
            collision(alias = false, pending = true)
        }

    @Test
    fun aliasCollisionMergesIntoActiveOwnerAndIngests() =
        runTest {
            collision(alias = true, pending = false)
        }

    @Test
    fun aliasCollisionUsesPendingSurvivorMode() =
        runTest {
            collision(alias = true, pending = true)
        }

    @Test
    fun moveRollsBackIfTheIngestCannotCommit() =
        runTest {
            val id = seed { copy(etag = "old", contentSha256 = "old-sha") }
            db.useWriterConnection { writer ->
                writer.usePrepared(
                    "CREATE TRIGGER fail_move_ingest BEFORE INSERT ON episode" +
                        " BEGIN SELECT RAISE(ABORT, 'ingest failed'); END",
                ) { it.step() }
            }
            server.enqueue(redirect(301, "/new"))
            server.enqueue(mockResponse(200, body))

            assertIs<FeedOutcome.Failed>(refresh(id))
            val stored = assertNotNull(db.podcastDao().byId(id))
            assertEquals(url("/old"), stored.feedUrl)
            assertEquals(key(url("/old")), stored.feedKey)
            assertEquals("old", stored.etag)
            assertEquals("old-sha", stored.contentSha256)
            assertTrue(db.podcastDao().aliases(id).isEmpty())
            assertEquals(0, db.podcastDao().episodeCount(id))
        }

    @Test
    fun collisionRevalidatesAliasOwnerAfterWaitingForCoordinator() =
        runTest {
            val loser = seed()
            val previousOwner = seed(url("/previous-owner"))
            val currentOwner = seed(url("/current-owner"))
            val target = key(url("/target"))
            db.podcastDao().insertAlias(PodcastUrlAliasEntity(target, previousOwner, AliasReason.REDIRECT, NOW))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val delegate = FakeCredentialCommitCoordinator()
            val coordinator =
                object : CredentialCommitCoordinator {
                    override suspend fun <T> withCredentialCommit(block: suspend () -> T): T {
                        entered.complete(Unit)
                        release.await()
                        return delegate.withCredentialCommit(block)
                    }
                }
            server.enqueue(redirect(301, "/target"))
            server.enqueue(mockResponse(200, body))
            newRssAdapter(root, clock).use { bundle ->
                val moves = FeedMoveCommitter(db, PodcastMerger(db, coordinator, clock), clock)
                val engine =
                    newRefresher(
                        db,
                        mapOf(SourceType.RSS to bundle.adapter),
                        clock,
                        tempFiles = bundle.tempFiles,
                        moves = moves,
                        scheduler = scheduler,
                    )
                val run = async { engine.run(request(loser)) }
                entered.await()
                // This writer must remain available while the merger waits for coordination.
                db.withWriteTransaction {
                    db.podcastDao().deleteAlias(previousOwner, target)
                    db.podcastDao().insertAlias(PodcastUrlAliasEntity(target, currentOwner, AliasReason.REDIRECT, NOW))
                }
                release.complete(Unit)
                assertEquals(FeedOutcome.Merged(currentOwner), run.await().outcomes[loser])
            }
            assertNotNull(db.podcastDao().byId(previousOwner))
            assertEquals(0, db.podcastDao().episodeCount(previousOwner))
            assertNull(db.podcastDao().byId(loser))
            assertEquals(1, db.podcastDao().episodeCount(currentOwner))
            assertEquals(1, delegate.commits)
            assertEquals(1, scheduler.rescheduleCount)
        }

    @Test
    fun rescheduleFailureDoesNotDiscardTheCommittedMergeReport() =
        runTest {
            val loser = seed()
            val winner = seed(url("/target"))
            server.enqueue(redirect(301, "/target"))
            server.enqueue(mockResponse(200, body))
            val faultingScheduler =
                object : RefreshScheduler by scheduler {
                    override suspend fun reschedulePeriodic() {
                        scheduler.reschedulePeriodic()
                        error("scheduler unavailable")
                    }
                }
            newRssAdapter(root, clock).use { bundle ->
                val engine =
                    newRefresher(
                        db,
                        mapOf(SourceType.RSS to bundle.adapter),
                        clock,
                        tempFiles = bundle.tempFiles,
                        scheduler = faultingScheduler,
                    )
                assertEquals(FeedOutcome.Merged(winner), engine.run(request(loser)).outcomes[loser])
            }
            assertNull(db.podcastDao().byId(loser))
            assertEquals(1, db.podcastDao().episodeCount(winner))
            assertEquals(1, scheduler.rescheduleCount)
        }

    private fun announcedBody(
        target: String,
        title: String = "Test Show",
        guid: String = "episode",
    ): String =
        rssBody(title = title, items = arrayOf(rssItem(guid)))
            .replace(
                "<rss version=\"2.0\">",
                "<rss version=\"2.0\" xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\">",
            ).replace("</channel>", "<itunes:new-feed-url>$target</itunes:new-feed-url></channel>")

    @Test
    fun plausibleAnnouncementAdoptsWithNewFeedUrlAlias() =
        runTest {
            val id = seed()
            server.enqueue(mockResponse(200, announcedBody(url("/new"))))
            server.enqueue(mockResponse(200, body, "ETag" to "target-validator"))

            assertIs<FeedOutcome.Ingested>(refresh(id))
            assertMove(id, url("/new"), key(url("/old")), AliasReason.NEW_FEED_URL)
            assertNull(db.podcastDao().byId(id)?.pendingNewFeedUrl)
            assertEquals("/old", server.takeRequest().url.encodedPath)
            val target = server.takeRequest()
            assertEquals("/new", target.url.encodedPath)
            assertNull(target.headers["If-None-Match"])
            assertNull(target.headers["If-Modified-Since"])
            assertEquals(2, server.requestCount)
        }

    @Test
    fun implausibleAnnouncementStaysPending() =
        runTest {
            val id = seed()
            server.enqueue(mockResponse(200, announcedBody(url("/new"))))
            val unrelated = rssBody(title = "Unrelated programme", items = arrayOf(rssItem("different")))
            server.enqueue(mockResponse(200, unrelated))

            assertIs<FeedOutcome.Ingested>(refresh(id))
            val stored = assertNotNull(db.podcastDao().byId(id))
            assertEquals(url("/old"), stored.feedUrl)
            assertEquals(url("/new"), stored.pendingNewFeedUrl)
            assertTrue(db.podcastDao().aliases(id).isEmpty())
            assertEquals(1, db.podcastDao().episodeCount(id))
            assertEquals(2, server.requestCount)
        }

    @Test
    fun failedAnnouncementTargetKeepsSuccessfulCurrentIngestAndPending() =
        runTest {
            val id = seed()
            server.enqueue(mockResponse(200, announcedBody(url("/new"))))
            server.enqueue(mockResponse(404, ""))

            assertIs<FeedOutcome.Ingested>(refresh(id))
            val stored = assertNotNull(db.podcastDao().byId(id))
            assertEquals(url("/new"), stored.pendingNewFeedUrl)
            assertEquals(0, stored.failureCount)
            assertNull(stored.lastErrorKind)
            assertEquals(1, db.podcastDao().episodeCount(id))
        }

    @Test
    fun notModifiedNeverProbesPendingAnnouncement() =
        runTest {
            val id =
                seed {
                    copy(
                        etag = "validator",
                        parserVersion = FeedParser.VERSION,
                        lastParseOk = true,
                        lastFullFetchAt = NOW,
                        pendingNewFeedUrl = url("/new"),
                    )
                }
            server.enqueue(mockResponse(304, ""))

            assertEquals(FeedOutcome.NotModified, refresh(id))
            assertEquals(1, server.requestCount)
            assertEquals(url("/new"), db.podcastDao().byId(id)?.pendingNewFeedUrl)
        }

    @Test
    fun unchangedNeverProbesPendingAnnouncement() =
        runTest {
            val tagged = announcedBody(url("/new"))
            val hash = tagged.encodeUtf8().sha256().hex()
            val id =
                seed {
                    copy(
                        contentSha256 = hash,
                        parserVersion = FeedParser.VERSION,
                        lastParseOk = true,
                        lastFullFetchAt = NOW,
                        pendingNewFeedUrl = url("/new"),
                    )
                }
            server.enqueue(mockResponse(200, tagged))

            assertEquals(FeedOutcome.Unchanged, refresh(id))
            assertEquals(1, server.requestCount)
            assertEquals(url("/new"), db.podcastDao().byId(id)?.pendingNewFeedUrl)
        }

    @Test
    fun redirect304ConfirmationNeverProbesItsTag() =
        runTest {
            val id =
                seed {
                    copy(
                        etag = "validator",
                        parserVersion = FeedParser.VERSION,
                        lastParseOk = true,
                        lastFullFetchAt = NOW,
                    )
                }
            server.enqueue(redirect(301, "/permanent"))
            server.enqueue(mockResponse(304, ""))
            server.enqueue(redirect(301, "/permanent"))
            server.enqueue(mockResponse(200, announcedBody(url("/tag-target"))))

            assertIs<FeedOutcome.Ingested>(refresh(id))
            assertEquals(url("/permanent"), db.podcastDao().byId(id)?.feedUrl)
            assertEquals(url("/tag-target"), db.podcastDao().byId(id)?.pendingNewFeedUrl)
            assertEquals(4, server.requestCount)
        }

    @Test
    fun announcementMergesIntoPrimaryOwner() =
        runTest {
            announcementCollision(alias = false)
        }

    @Test
    fun announcementMergesIntoAliasOwner() =
        runTest {
            announcementCollision(alias = true)
        }

    private suspend fun announcementCollision(alias: Boolean) {
        val loser = seed()
        val winner = seed(if (alias) url("/winner") else url("/new"))
        if (alias) {
            db.podcastDao().insertAlias(
                PodcastUrlAliasEntity(key(url("/new")), winner, AliasReason.REDIRECT, NOW),
            )
        }
        server.enqueue(mockResponse(200, announcedBody(url("/new"))))
        server.enqueue(mockResponse(200, rssBody(items = arrayOf(rssItem("episode"), rssItem("new-episode")))))

        assertEquals(FeedOutcome.Merged(winner), refresh(loser))
        assertNull(db.podcastDao().byId(loser))
        val stored = assertNotNull(db.podcastDao().byId(winner))
        assertEquals(if (alias) url("/winner") else url("/new"), stored.feedUrl)
        assertNull(stored.pendingNewFeedUrl)
        assertEquals(2, db.podcastDao().episodeCount(winner))
        assertEquals(1, scheduler.rescheduleCount)
    }

    @Test
    fun chainedAnnouncementsAdoptEachHopAndStopAliasLoop() =
        runTest {
            val id = seed()
            server.enqueue(mockResponse(200, announcedBody(url("/middle"))))
            server.enqueue(mockResponse(200, announcedBody(url("/new"))))
            server.enqueue(mockResponse(200, announcedBody(url("/old"))))

            assertIs<FeedOutcome.Ingested>(refresh(id))
            val stored = assertNotNull(db.podcastDao().byId(id))
            assertEquals(url("/new"), stored.feedUrl)
            assertNull(stored.pendingNewFeedUrl)
            val aliases = db.podcastDao().aliases(id)
            assertEquals(setOf(key(url("/old")), key(url("/middle"))), aliases.map { it.url }.toSet())
            assertTrue(aliases.all { it.reason == AliasReason.NEW_FEED_URL })
            assertEquals(3, server.requestCount)
        }

    @Test
    fun rejectedSecondHopKeepsFirstMoveAndSecondPending() =
        runTest {
            val id = seed()
            server.enqueue(mockResponse(200, announcedBody(url("/middle"))))
            server.enqueue(mockResponse(200, announcedBody(url("/new"))))
            server.enqueue(mockResponse(404, ""))

            assertIs<FeedOutcome.Ingested>(refresh(id))
            assertEquals(url("/middle"), db.podcastDao().byId(id)?.feedUrl)
            assertEquals(url("/new"), db.podcastDao().byId(id)?.pendingNewFeedUrl)
            assertEquals(
                AliasReason.NEW_FEED_URL,
                db
                    .podcastDao()
                    .aliases(id)
                    .single()
                    .reason,
            )
        }

    @Test
    fun announcementMoveRollsBackWithFailedTargetIngest() =
        runTest {
            val id = seed()
            db.useWriterConnection { writer ->
                writer.usePrepared(
                    "CREATE TRIGGER fail_target_ingest BEFORE INSERT ON episode" +
                        " WHEN NEW.guid = 'target-only'" +
                        " BEGIN SELECT RAISE(ABORT, 'target failed'); END",
                ) { it.step() }
            }
            server.enqueue(mockResponse(200, announcedBody(url("/new"))))
            server.enqueue(mockResponse(200, rssBody(items = arrayOf(rssItem("episode"), rssItem("target-only")))))

            assertIs<FeedOutcome.Ingested>(refresh(id))
            val stored = assertNotNull(db.podcastDao().byId(id))
            assertEquals(url("/old"), stored.feedUrl)
            assertEquals(url("/new"), stored.pendingNewFeedUrl)
            assertTrue(db.podcastDao().aliases(id).isEmpty())
            assertEquals(1, db.podcastDao().episodeCount(id))
        }

    @Test
    fun announcementProbeUsesOnlyTargetOriginCredentials() =
        runTest {
            val second = MockWebServer()
            try {
                second.start()
                val target = second.url("/new").toString()
                val id = seed()
                for (targetCredential in listOf(null, "Basic dGFyZ2V0")) {
                    val credentials =
                        CredentialLookup { origin ->
                            when (origin.port) {
                                server.port -> "Basic c291cmNl"
                                second.port -> targetCredential
                                else -> null
                            }
                        }
                    // Each iteration starts at the original subscription address.
                    db.podcastDao().moveFeed(id, url("/old"), key(url("/old")))
                    db.podcastDao().deleteAlias(id, key(url("/old")))
                    server.enqueue(mockResponse(200, announcedBody(target)))
                    second.enqueue(mockResponse(200, body))
                    newRssAdapter(root, clock, credentials = credentials).use { bundle ->
                        val engine =
                            newRefresher(
                                db,
                                mapOf(SourceType.RSS to bundle.adapter),
                                clock,
                                tempFiles = bundle.tempFiles,
                            )
                        assertIs<FeedOutcome.Ingested>(engine.run(request(id)).outcomes[id])
                    }
                    assertEquals("Basic c291cmNl", server.takeRequest().headers["Authorization"])
                    val probe = second.takeRequest()
                    assertEquals(targetCredential, probe.headers["Authorization"])
                    assertNull(probe.headers["If-None-Match"])
                    assertNull(probe.headers["If-Modified-Since"])
                    assertEquals(target, db.podcastDao().byId(id)?.feedUrl)
                }
            } finally {
                second.close()
            }
        }

    private companion object {
        const val NOW = 1_791_000_000_000L
        const val DAY = 24L * 60 * 60 * 1000
    }
}
