// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.discovery.AppleDirectoryClient
import ch.lkmc.neutrodyne.core.data.discovery.PodcastIndexDirectoryClient
import ch.lkmc.neutrodyne.core.data.fetch.FeedRequest
import ch.lkmc.neutrodyne.core.data.fetch.FetchOutcome
import ch.lkmc.neutrodyne.core.data.fetch.Sniff
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.AddResolution
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.DirectoryAccess
import ch.lkmc.neutrodyne.core.model.DirectoryHit
import ch.lkmc.neutrodyne.core.model.ProviderId
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.feeds.discovery.Autodiscovery
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.junit4.MockWebServerRule
import okio.FileSystem
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class AddDiscoveryTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server
    private val clock = TestClock()
    private val db = newDb(clock)
    private val root =
        kotlin.io.path
            .createTempDirectory("nd-discovery")
            .toFile()
    private lateinit var bundle: ResolverBundle
    private var appleAnswer: Outcome<ImmutableList<DirectoryHit>, ProviderStatus> =
        Outcome.Failure(ProviderStatus.Disabled)
    private val accesses = mutableListOf<DirectoryAccess>()
    private var indexAnswer: Outcome<DirectoryHit?, ProviderStatus> = Outcome.Failure(ProviderStatus.Disabled)
    private val apple =
        object : AppleDirectoryClient by unusedApple {
            override suspend fun lookup(
                ids: ImmutableList<String>,
                access: DirectoryAccess,
            ): Outcome<ImmutableList<DirectoryHit>, ProviderStatus> {
                accesses += access
                return appleAnswer
            }
        }
    private val index =
        object : PodcastIndexDirectoryClient {
            override suspend fun lookup(feedId: String): Outcome<DirectoryHit?, ProviderStatus> = indexAnswer
        }

    @Before fun setup() {
        bundle = newResolver(root, db, clock, apple = apple, podcastIndex = index)
    }

    @After fun cleanup() {
        bundle.close()
        db.close()
        root.deleteRecursively()
    }

    private fun url(path: String) = server.url(path).toString()

    private fun fixture(name: String) =
        checkNotNull(javaClass.getResourceAsStream("/discovery/$name")).bufferedReader().use {
            it.readText()
        }

    @Test fun alternateFeedResolvesAndNeverWritesBeforeSubscribe() =
        runBlocking {
            server.enqueue(
                mockResponse(
                    body =
                        "<html><head><link rel='alternate' type='application/rss+xml' " +
                            "href='/podcast.xml'></head></html>",
                ),
            )
            server.enqueue(mockResponse(body = rssBody(title = "Discovered", items = arrayOf(rssItem("e1")))))
            val result = assertIs<AddResolution.Feed>(bundle.resolver.resolve(url("/page")))
            assertEquals("Discovered", result.preview.title)
            assertEquals(url("/podcast.xml"), result.preview.feedUrl)
            assertTrue(db.podcastDao().allForExport().isEmpty())
            assertEquals(2, server.requestCount)
            assertTempFilesEmpty()
        }

    @Test fun appleOnlyAnchorUsesSharedLookupAndArtworkFallback() =
        runBlocking {
            appleAnswer =
                Outcome.Success(
                    persistentListOf(
                        DirectoryHit(
                            url("/actual.xml"),
                            "Directory title",
                            artworkUrl = "https://art.example/cover.jpg",
                            providers = persistentSetOf(ProviderId.APPLE),
                        ),
                    ),
                )
            server.enqueue(mockResponse(body = fixture("apple-only.html")))
            server.enqueue(mockResponse(body = rssBody(title = "Publisher title")))
            val preview = assertIs<AddResolution.Feed>(bundle.resolver.resolve(url("/page"))).preview
            assertEquals("Publisher title", preview.title)
            assertEquals("https://art.example/cover.jpg", preview.artworkUrl)
            assertEquals(listOf(DirectoryAccess.ENABLED_ONLY), accesses)
            assertEquals(
                "https://art.example/cover.jpg",
                bundle.cache
                    .get(preview.previewId)
                    ?.feed
                    ?.artwork
                    ?.single()
                    ?.url,
            )
        }

    @Test fun disabledAppleRequiresOneTimeConsentAndMissingFeedUrlExplainsAppleOnlyShow() =
        runBlocking {
            val input = "https://pod.link/73329284"
            assertIs<AddResolution.LookupRequired>(bundle.resolver.resolve(input))
            assertEquals(0, server.requestCount)
            appleAnswer = Outcome.Success(persistentListOf())
            assertEquals(
                AddPodcastError.AppleOnlyShow,
                assertIs<AddResolution.Failure>(bundle.resolver.lookupOnce(input)).error,
            )
            assertEquals(listOf(DirectoryAccess.ENABLED_ONLY, DirectoryAccess.ONE_TIME_LOOKUP), accesses)
        }

    @Test fun wordpressBlogFeedFallsBackToPodcastFeed() =
        runBlocking {
            server.enqueue(mockResponse(body = fixture("wordpress.html")))
            server.enqueue(mockResponse(body = rssBody(items = arrayOf("<item><title>A blog post</title></item>"))))
            server.enqueue(mockResponse(body = rssBody(title = "Podcast", items = arrayOf(rssItem("e")))))
            val preview = assertIs<AddResolution.Feed>(bundle.resolver.resolve(url("/page"))).preview
            assertEquals(url("/feed/podcast"), preview.feedUrl)
            assertEquals(listOf("/page", "/feed", "/feed/podcast"), (1..3).map { server.takeRequest().target })
        }

    @Test fun multipleFeedsProduceChooserAndSelectedFeedCanBeResolvedAgain() =
        runBlocking {
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        mockResponse(
                            body =
                                when (request.target) {
                                    "/page" -> fixture("multiple.html")
                                    "/one.xml" -> rssBody(title = "One")
                                    else -> rssBody(title = "Two")
                                },
                        )
                }
            val choices = assertIs<AddResolution.Choose>(bundle.resolver.resolve(url("/page"))).candidates
            assertEquals(listOf("One", "Two"), choices.map { it.title })
            assertEquals("Two", assertIs<AddResolution.Feed>(bundle.resolver.resolve(choices.last().url)).preview.title)
            assertTempFilesEmpty()
        }

    @Test fun candidateFanoutIsFiveAndConcurrencyIsTwoWithoutFollowingHtmlCycles() =
        runBlocking {
            val active = AtomicInteger()
            val peak = AtomicInteger()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.target ==
                            "/page"
                        ) {
                            return mockResponse(
                                body =
                                    "<html><head>" +
                                        (1..8).joinToString(
                                            "",
                                        ) { "<link rel='alternate' type='text/xml' href='/$it.xml'>" } +
                                        "</head></html>",
                            )
                        }
                        val count = active.incrementAndGet()
                        peak.accumulateAndGet(count, ::maxOf)
                        Thread.sleep(50)
                        active.decrementAndGet()
                        return mockResponse(
                            body = "<html><head><link rel='alternate' type='text/xml' href='/page'></head></html>",
                        )
                    }
                }
            assertEquals(
                AddPodcastError.NotAFeed,
                assertIs<AddResolution.Failure>(bundle.resolver.resolve(url("/page"))).error,
            )
            assertEquals(6, server.requestCount)
            assertTrue(peak.get() <= 2)
            assertTempFilesEmpty()
        }

    @Test fun probesAreSequentialFiveAndUseBoundedReads(): Unit =
        runBlocking {
            server.enqueue(mockResponse(body = "<html><body>No links</body></html>"))
            repeat(5) { server.enqueue(mockResponse(404, "no")) }
            assertIs<AddResolution.Failure>(bundle.resolver.resolve(url("/page")))
            assertEquals(
                listOf("/page", "/feed/podcast", "/podcast.xml", "/feed", "/rss", "/rss.xml"),
                (1..6).map {
                    server.takeRequest().target
                },
            )
            val fetcher = newFetcher(root)
            try {
                server.enqueue(mockResponse(body = "<rss><channel>" + " ".repeat(100_000)))
                val body =
                    assertIs<FetchOutcome.Body>(
                        fetcher.fetcher.fetch(
                            FeedRequest(
                                url("/probe"),
                                null,
                                null,
                                false,
                                sniffOnlyBytes = Autodiscovery.PROBE_BYTES.toInt(),
                            ),
                        ),
                    )
                assertEquals(Autodiscovery.PROBE_BYTES, FileSystem.SYSTEM.metadata(body.file).size)
                fetcher.tempFiles.delete(body.file)
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(404)
                        .body(" ".repeat(256 * 1024))
                        .throttleBody(Autodiscovery.PROBE_BYTES, 30, TimeUnit.SECONDS)
                        .build(),
                )
                withTimeout(5000) {
                    assertIs<FetchOutcome.Http>(
                        fetcher.fetcher.fetch(
                            FeedRequest(
                                url("/error-probe"),
                                null,
                                null,
                                false,
                                sniffOnlyBytes = Autodiscovery.PROBE_BYTES.toInt(),
                            ),
                        ),
                    )
                }
            } finally {
                fetcher.close()
            }
        }

    @Test fun htmlTransportStopsAtTwoMiBBeforeDelayedTailAndFeedsKeepTheirFullLimit() =
        runBlocking {
            val fetcher = newFetcher(root)
            try {
                server.enqueue(
                    MockResponse
                        .Builder()
                        .body("<html>" + " ".repeat(3 * 1024 * 1024))
                        .throttleBody(Autodiscovery.MAX_HTML_BYTES, 30, TimeUnit.SECONDS)
                        .build(),
                )
                val html =
                    withTimeout(5000) {
                        assertIs<FetchOutcome.Body>(
                            fetcher.fetcher.fetch(
                                FeedRequest(
                                    url("/large"),
                                    null,
                                    null,
                                    false,
                                    htmlReadLimit = Autodiscovery.MAX_HTML_BYTES,
                                ),
                            ),
                        )
                    }
                assertEquals(Sniff.HTML, html.sniff)
                assertEquals(Autodiscovery.MAX_HTML_BYTES, FileSystem.SYSTEM.metadata(html.file).size)
                fetcher.tempFiles.delete(html.file)
                server.enqueue(mockResponse(body = "<rss><channel>" + " ".repeat(3 * 1024 * 1024)))
                val feed =
                    assertIs<FetchOutcome.Body>(
                        fetcher.fetcher.fetch(
                            FeedRequest(url("/feed"), null, null, false, htmlReadLimit = Autodiscovery.MAX_HTML_BYTES),
                        ),
                    )
                assertTrue(checkNotNull(FileSystem.SYSTEM.metadata(feed.file).size) > Autodiscovery.MAX_HTML_BYTES)
                fetcher.tempFiles.delete(feed.file)
            } finally {
                fetcher.close()
            }
        }

    @Test fun credentialsStayOnOriginalOriginForCandidatesAndIdLookupFeeds() =
        runBlocking {
            val other = mockwebserver3.MockWebServer()
            other.start()
            try {
                other.enqueue(mockResponse(body = rssBody()))
                server.enqueue(
                    mockResponse(
                        body = "<html><head><link rel='alternate' type='text/xml' href='${other.url(
                            "/feed",
                        )}'></head></html>",
                    ),
                )
                assertIs<AddResolution.Feed>(bundle.resolver.resolve(url("/page"), BasicCredentials("u", "p")))
                assertEquals("Basic dTpw", server.takeRequest().headers["Authorization"])
                assertEquals(null, other.takeRequest().headers["Authorization"])
                appleAnswer =
                    Outcome.Success(
                        persistentListOf(
                            DirectoryHit(url("/actual"), "Show", providers = persistentSetOf(ProviderId.APPLE)),
                        ),
                    )
                server.enqueue(mockResponse(body = rssBody()))
                assertIs<AddResolution.Feed>(bundle.resolver.resolve("https://pod.link/12", BasicCredentials("u", "p")))
                assertEquals(null, server.takeRequest().headers["Authorization"])
            } finally {
                other.close()
            }
        }

    @Test fun youtubeIsCheckedForRawTokensLookupCandidatesAndRedirects() =
        runBlocking {
            for (raw in listOf(
                "@mkbhd",
                "Subscribe to @some.name",
                "UCBJycsmduvYEL83R_U4JriQ",
                "neutrodyne://subscribe?url=https%3A%2F%2Fyoutube.com%2F%40someone",
            )) {
                assertEquals(
                    AddPodcastError.YouTubeNotYetSupported,
                    assertIs<AddResolution.Failure>(bundle.resolver.resolve(raw)).error,
                )
            }
            appleAnswer =
                Outcome.Success(
                    persistentListOf(
                        DirectoryHit(
                            "https://www.youtube.com/feeds/videos.xml?channel_id=UCBJycsmduvYEL83R_U4JriQ",
                            "YT",
                            providers = persistentSetOf(ProviderId.APPLE),
                        ),
                    ),
                )
            assertEquals(
                AddPodcastError.YouTubeNotYetSupported,
                assertIs<AddResolution.Failure>(bundle.resolver.resolve("https://pod.link/12")).error,
            )
            server.enqueue(
                mockResponse(
                    body =
                        "<html><head><link rel='alternate' type='text/xml' " +
                            "href='https://youtube.com/@someone'></head></html>",
                ),
            )
            assertEquals(
                AddPodcastError.YouTubeNotYetSupported,
                assertIs<AddResolution.Failure>(bundle.resolver.resolve(url("/page"))).error,
            )
            server.enqueue(mockResponse(302, "", "Location" to "https://youtube.com/@someone"))
            assertEquals(
                AddPodcastError.YouTubeNotYetSupported,
                assertIs<AddResolution.Failure>(bundle.resolver.resolve(url("/redirect"))).error,
            )
            assertEquals(2, server.requestCount)
            server.enqueue(mockResponse(body = "<html>No links</html>"))
            server.enqueue(mockResponse(302, "", "Location" to "https://youtube.com/@someone"))
            assertEquals(
                AddPodcastError.YouTubeNotYetSupported,
                assertIs<AddResolution.Failure>(bundle.resolver.resolve(url("/probe-page"))).error,
            )
            assertEquals(4, server.requestCount)
            assertTempFilesEmpty()
        }

    @Test fun cancellationDeletesHtmlAndCandidateTemporaryFiles() =
        runBlocking {
            server.enqueue(mockResponse(body = fixture("multiple.html")))
            repeat(2) {
                server.enqueue(
                    MockResponse
                        .Builder()
                        .body(rssBody())
                        .bodyDelay(30, TimeUnit.SECONDS)
                        .build(),
                )
            }
            val job = launch(Dispatchers.Default) { bundle.resolver.resolve(url("/page")) }
            withTimeout(5000) { while (server.requestCount < 3) kotlinx.coroutines.delay(10) }
            job.cancelAndJoin()
            assertTempFilesEmpty()
        }

    @Test fun expiredPreviewRefetchesAndDirectEditNeverDiscovers() =
        runBlocking {
            server.enqueue(mockResponse(body = rssBody(title = "Before")))
            val first = assertIs<AddResolution.Feed>(bundle.resolver.resolve(url("/a.xml"))).preview
            clock.advanceBy(16.minutes)
            server.enqueue(mockResponse(body = rssBody(title = "After")))
            assertEquals(
                "After",
                assertIs<Outcome.Success<*>>(bundle.resolver.preview(first.feedUrl)).value.let {
                    (it as ch.lkmc.neutrodyne.core.model.FeedPreview).title
                },
            )
            server.enqueue(mockResponse(body = fixture("multiple.html")))
            assertEquals(Outcome.Failure(AddPodcastError.NotAFeed), bundle.resolver.probe(url("/page")))
            assertEquals(3, server.requestCount)
            assertTempFilesEmpty()
        }

    @Test fun resolvedArtworkAndMembershipSurviveSubscribeAndCredentialsKeepTheirOrigin() =
        runBlocking {
            val group = db.groupDao().insert(groupEntity(name = "G", orderKey = "a", createdAt = clock.now()))
            appleAnswer =
                Outcome.Success(
                    persistentListOf(
                        DirectoryHit(
                            url("/actual"),
                            "Show",
                            artworkUrl = "https://art.example/cover",
                            providers = persistentSetOf(ProviderId.APPLE),
                        ),
                    ),
                )
            server.enqueue(mockResponse(302, "", "Location" to url("/landed").replace("localhost", "127.0.0.1")))
            server.enqueue(mockResponse(body = rssBody(items = arrayOf(rssItem("e")))))
            val preview = assertIs<AddResolution.Feed>(bundle.resolver.resolve("https://pod.link/12")).preview
            val subscribe =
                newSubscribe(db, bundle.cache, bundle.resolver, FakeRefreshScheduler(), FakeSettingsRepository(), clock)
            val id = assertIs<Outcome.Success<Long>>(subscribe.useCase(preview.previewId, setOf(group))).value
            assertEquals("https://art.example/cover", db.podcastDao().byId(id)?.artworkUrl)
            assertEquals(
                id,
                db
                    .groupDao()
                    .membersOf(group)
                    .single()
                    .podcastId,
            )

            server.enqueue(mockResponse(302, "", "Location" to url("/landed").replace("localhost", "127.0.0.1")))
            server.enqueue(mockResponse(body = rssBody()))
            val credentialed =
                assertIs<AddResolution.Feed>(
                    bundle.resolver.resolve(url("/private"), BasicCredentials("u", "p")),
                ).preview
            assertEquals(BasicCredentials("u", "p"), bundle.cache.get(credentialed.previewId)?.credentials)
            repeat(2) { server.takeRequest() }
            assertEquals("Basic dTpw", server.takeRequest().headers["Authorization"])
            assertEquals(null, server.takeRequest().headers["Authorization"])
        }

    @Test fun indexLookupIsKeyGatedAndPublisherUrlsAreRechecked() =
        runBlocking {
            indexAnswer =
                Outcome.Success(
                    DirectoryHit(url("/indexed"), "Indexed", providers = persistentSetOf(ProviderId.PODCAST_INDEX)),
                )
            server.enqueue(mockResponse(body = rssBody(title = "Indexed")))
            assertEquals(
                "Indexed",
                assertIs<AddResolution.Feed>(
                    bundle.resolver.resolve("https://podcastindex.org/podcast/1"),
                ).preview.title,
            )
            indexAnswer =
                Outcome.Success(
                    DirectoryHit(
                        "https://youtube.com/@someone",
                        "YT",
                        providers = persistentSetOf(ProviderId.PODCAST_INDEX),
                    ),
                )
            assertEquals(
                AddPodcastError.YouTubeNotYetSupported,
                assertIs<AddResolution.Failure>(bundle.resolver.resolve("https://podcastindex.org/podcast/2")).error,
            )
            assertEquals(1, server.requestCount)
        }

    @Test fun rootProbeBudgetCancelsSlowBodiesWithinFifteenSeconds() =
        runBlocking {
            server.enqueue(mockResponse(body = "<html>No links</html>"))
            server.enqueue(
                MockResponse
                    .Builder()
                    .body("<rss><channel/>")
                    .bodyDelay(30, TimeUnit.SECONDS)
                    .build(),
            )
            withTimeout(17_000) {
                assertEquals(
                    AddPodcastError.NotAFeed,
                    assertIs<AddResolution.Failure>(bundle.resolver.resolve(url("/page"))).error,
                )
            }
            assertEquals(2, server.requestCount)
            assertTempFilesEmpty()
        }

    @Test fun inputResolutionUsesIoDispatcher() =
        runBlocking {
            val dispatches = AtomicInteger()
            val observedIo =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) {
                        dispatches.incrementAndGet()
                        Dispatchers.Default.dispatch(context, block)
                    }
                }
            bundle.close()
            bundle = newResolver(root, db, clock, io = observedIo)
            assertIs<AddResolution.Failure>(bundle.resolver.resolve("plain words"))
            assertTrue(dispatches.get() > 0, "Add resolution did not use the injected I/O dispatcher")
            assertEquals(0, server.requestCount)
        }

    private fun assertTempFilesEmpty() {
        val remaining =
            root
                .resolve("cache/feeds")
                .walkTopDown()
                .filter { it.isFile }
                .map { "${it.name}:${it.length()}" }
                .toList()
        assertTrue(remaining.isEmpty(), "Temporary response leaked: $remaining")
    }
}
