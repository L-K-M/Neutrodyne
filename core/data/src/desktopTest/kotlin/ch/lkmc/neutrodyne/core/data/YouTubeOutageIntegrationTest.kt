// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.data.refresh.AdapterResult
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshRequest
import ch.lkmc.neutrodyne.core.data.youtube.DefaultYouTubeHealth
import ch.lkmc.neutrodyne.core.data.youtube.YouTubeControlCheck
import ch.lkmc.neutrodyne.core.data.youtube.YouTubeOutageMonitor
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.network.okhttp.AuthInterceptor
import ch.lkmc.neutrodyne.core.network.okhttp.CoreClients
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import ch.lkmc.neutrodyne.core.network.okhttp.LocalNetworkGuard
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import ch.lkmc.neutrodyne.core.network.okhttp.UserAgentInterceptor
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.api.FeedGate
import ch.lkmc.neutrodyne.youtube.api.FeedOutcome
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedOutageMonitor
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.Interceptor
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** W3 supplies the adapter; W4 verifies its frozen health ports against real refresh persistence. */
@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeOutageIntegrationTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    @Test fun deferredChannelChangesOnlyNextRefreshAtAndFinalizationUsesWholeLibrary() =
        runTest {
            val clock = TestClock()
            val db = newDb(clock, queryContext = StandardTestDispatcher(testScheduler))
            try {
                val id =
                    seedPodcast(
                        db,
                        "https://www.youtube.com/feeds/videos.xml?channel_id=test",
                        failureCount = 7,
                        lastAttemptAt = clock.now() - 1000,
                        etag = "keep",
                        block = {
                            copy(
                                sourceType = SourceType.YOUTUBE_CHANNEL,
                                lastErrorKind = FeedErrorKind.HTTP_NOT_FOUND,
                                lastErrorDetail = "keep",
                            )
                        },
                    )
                seedPodcast(db, "https://example.com/rss")
                seedPodcast(
                    db,
                    "https://www.youtube.com/feeds/videos.xml?channel_id=other",
                    nextRefreshAt =
                        clock.now() + 9999,
                    block = { copy(sourceType = SourceType.YOUTUBE_CHANNEL) },
                )
                val before = db.podcastDao().byId(id)!!
                val finished = mutableListOf<Int>()
                val monitor =
                    object : YouTubeFeedOutageMonitor by ch.lkmc.neutrodyne.core.testing
                        .FakeYouTubeFeedOutageMonitor() {
                        override suspend fun onRunFinished(
                            totalSubscribed: Int,
                            nowMs: Long,
                        ) {
                            finished += totalSubscribed
                        }
                    }
                val until = clock.now() + 3_600_000
                val adapter = stubAdapter(onFetch = { _, _ -> AdapterResult.Deferred(until) })
                newRefresher(db, mapOf(SourceType.YOUTUBE_CHANNEL to adapter), clock, youTubeMonitor = monitor)
                    .run(request(RefreshScope.Podcasts(listOf(id))))
                assertEquals(before.copy(nextRefreshAt = until), db.podcastDao().byId(id))
                assertEquals(listOf(2), finished)
            } finally {
                db.close()
            }
        }

    @Test fun deletedChannelRemainsSubscribedAndNeverGone() =
        runTest {
            val clock = TestClock()
            val db = newDb(clock, queryContext = StandardTestDispatcher(testScheduler))
            val health = DefaultYouTubeHealth(FakeSettingsRepository(), clock, backgroundScope) {}
            val monitor = YouTubeOutageMonitor(health, clock, { 1 }) { FeedOutcome.SUCCESS }
            try {
                val id =
                    seedPodcast(
                        db,
                        YouTubeFeedUrls.canonical(YouTubeFeedUrls.YOUTUBE_CONTROL_CHANNEL_ID),
                        failureCount = 10,
                        block = { copy(sourceType = SourceType.YOUTUBE_CHANNEL) },
                    )
                val adapter =
                    stubAdapter(onFetch = { feed, _ ->
                        monitor.gate(feed.id, clock.now())
                        monitor.report(feed.id, FeedOutcome.FAILURE, clock.now())
                        AdapterResult.Failed(FeedErrorKind.HTTP_GONE, 410, null, transient = true)
                    })
                newRefresher(
                    db,
                    mapOf(SourceType.YOUTUBE_CHANNEL to adapter),
                    clock,
                    youTubeMonitor = monitor,
                ).run(request())
                val stored = db.podcastDao().byId(id)!!
                assertFalse(stored.gone)
                assertEquals(11, stored.failureCount)
                assertEquals(0, health.state.value.feedOutageLevel)
                assertEquals(listOf(id), db.podcastDao().youtubeChannelIds())
            } finally {
                db.close()
            }
        }

    @Test fun finalizationNetworkHonoursDeadlineAndStillFlushesRows() =
        runTest {
            val clock = TestClock()
            val db = newDb(clock, queryContext = StandardTestDispatcher(testScheduler))
            val entered = CompletableDeferred<Unit>()
            val monitor =
                object : YouTubeFeedOutageMonitor by ch.lkmc.neutrodyne.core.testing
                    .FakeYouTubeFeedOutageMonitor() {
                    override suspend fun onRunFinished(
                        totalSubscribed: Int,
                        nowMs: Long,
                    ) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            try {
                val id = seedPodcast(db, "https://example.com/deferred")
                val adapter =
                    stubAdapter(onFetch = { _, _ ->
                        clock.elapsedMs = 30_000
                        AdapterResult.Deferred(clock.now() + 100_000)
                    })
                val refresher = newRefresher(db, mapOf(SourceType.RSS to adapter), clock, youTubeMonitor = monitor)
                val run = async { refresher.run(request(deadline = 35_000)) }
                entered.await()
                advanceTimeBy(5_000)
                runCurrent()
                run.await()
                assertFalse(refresher.status.value.running)
                assertTrue(db.podcastDao().byId(id)!!.nextRefreshAt!! > clock.now())
            } finally {
                db.close()
            }
        }

    @Test fun cancellationInterruptsFinalizationButFlushesRows() =
        runTest {
            val clock = TestClock()
            val db = newDb(clock, queryContext = StandardTestDispatcher(testScheduler))
            val entered = CompletableDeferred<Unit>()
            val monitor =
                object : YouTubeFeedOutageMonitor by ch.lkmc.neutrodyne.core.testing
                    .FakeYouTubeFeedOutageMonitor() {
                    override suspend fun onRunFinished(
                        totalSubscribed: Int,
                        nowMs: Long,
                    ) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            try {
                val id = seedPodcast(db, "https://example.com/deferred")
                val until = clock.now() + 100_000
                val refresher =
                    newRefresher(
                        db,
                        mapOf(SourceType.RSS to stubAdapter(onFetch = { _, _ -> AdapterResult.Deferred(until) })),
                        clock,
                        youTubeMonitor = monitor,
                    )
                val run = async { refresher.run(request()) }
                entered.await()
                run.cancelAndJoin()
                assertTrue(run.isCancelled)
                assertFalse(refresher.status.value.running)
                assertEquals(until, db.podcastDao().byId(id)!!.nextRefreshAt)
            } finally {
                db.close()
            }
        }

    @Test fun controlsUseCanonicalFeedsStopAtSuccessAndDeleteBodies() =
        runTest {
            val root =
                kotlin.io.path
                    .createTempDirectory("nd-controls")
                    .toFile()
            val clock = TestClock()
            val health = DefaultYouTubeHealth(FakeSettingsRepository(), clock, backgroundScope) {}
            val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
            // Route production HTTPS URLs only in this test; the real FEED client/headers remain in use.
            val clients =
                NetworkClients(
                    CoreClients(
                        UserAgentInterceptor(testUserAgent()),
                        LocalNetworkGuard(LocalNetworkAccess(), fakePlatform()),
                        DnsFamilyHints(),
                        setOf(
                            Interceptor { chain ->
                                val url = chain.request().url
                                check(url.host == "www.youtube.com")
                                chain.proceed(
                                    chain
                                        .request()
                                        .newBuilder()
                                        .url(server.url(url.encodedPath + "?" + url.encodedQuery))
                                        .build(),
                                )
                            },
                        ),
                    ),
                    AuthInterceptor { CredentialLookup.None },
                )
            try {
                newFetcher(root, clock = clock, networkClients = clients).use { bundle ->
                    val controls = YouTubeControlCheck(bundle.fetcher, bundle.tempFiles, network, health, clock)
                    server.enqueue(mockResponse(404))
                    server.enqueue(mockResponse(200, "<feed/>"))
                    assertEquals(FeedOutcome.SUCCESS, controls.check())
                    val first = server.takeRequest()
                    assertEquals(
                        "/feeds/videos.xml?channel_id=${YouTubeFeedUrls.YOUTUBE_CONTROL_CHANNEL_ID}",
                        first.url.encodedPath + "?" + first.url.encodedQuery,
                    )
                    val second = server.takeRequest()
                    assertTrue(second.url.toString().contains(YouTubeFeedUrls.GOOGLE_CONTROL_CHANNEL_ID))
                    assertTrue(first.headers["User-Agent"]!!.contains("Neutrodyne"))
                    assertFalse(first.headers["Accept"]!!.contains("application/json"))
                    assertEquals(0, root.resolve("cache/feeds").listFiles()?.size ?: 0)
                    server.enqueue(mockResponse(200, "<feed/>"))
                    assertEquals(FeedOutcome.SUCCESS, controls.check())
                    assertEquals(3, server.requestCount)
                    network.setStatus(FakeNetworkMonitor.OFFLINE)
                    assertEquals(FeedOutcome.INCONCLUSIVE, controls.check())
                    assertEquals(3, server.requestCount)
                }
            } finally {
                root.deleteRecursively()
            }
        }

    @Test fun bothControlsMustFailAndRateLimitDoesNotDeclareOutage() =
        runTest {
            val root =
                kotlin.io.path
                    .createTempDirectory("nd-controls-status")
                    .toFile()
            val clock = TestClock()
            val health = DefaultYouTubeHealth(FakeSettingsRepository(), clock, backgroundScope) {}
            val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
            val clients =
                NetworkClients(
                    CoreClients(
                        UserAgentInterceptor(testUserAgent()),
                        LocalNetworkGuard(LocalNetworkAccess(), fakePlatform()),
                        DnsFamilyHints(),
                        setOf(
                            Interceptor { chain ->
                                chain.proceed(
                                    chain
                                        .request()
                                        .newBuilder()
                                        .url(server.url("/control"))
                                        .build(),
                                )
                            },
                        ),
                    ),
                    AuthInterceptor { CredentialLookup.None },
                )
            try {
                newFetcher(root, clock = clock, networkClients = clients).use { bundle ->
                    val controls = YouTubeControlCheck(bundle.fetcher, bundle.tempFiles, network, health, clock)
                    server.enqueue(mockResponse(404))
                    server.enqueue(mockResponse(503))
                    assertEquals(FeedOutcome.FAILURE, controls.check())
                    server.enqueue(mockResponse(429, "", "Retry-After" to "7200"))
                    assertEquals(FeedOutcome.INCONCLUSIVE, controls.check())
                    health.awaitLoaded()
                    assertEquals(clock.now() + 7_200_000, health.state.value.feedRateLimitedUntil)
                    assertEquals(0, health.state.value.feedOutageLevel)
                    assertEquals(3, server.requestCount)
                }
            } finally {
                root.deleteRecursively()
            }
        }

    private fun request(
        scope: RefreshScope = RefreshScope.All,
        deadline: Long = RefreshRequest.NO_DEADLINE,
    ) = RefreshRequest(
        scope,
        force = false,
        pagesOnly = false,
        origin = RefreshOrigin.PERIODIC,
        deadlineElapsedMs = deadline,
    )
}
