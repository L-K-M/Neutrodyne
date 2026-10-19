// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.discovery

import androidx.room3.useReaderConnection
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.fakePlatform
import ch.lkmc.neutrodyne.core.data.mockResponse
import ch.lkmc.neutrodyne.core.data.newDb
import ch.lkmc.neutrodyne.core.data.seedPodcast
import ch.lkmc.neutrodyne.core.data.testBuildInfo
import ch.lkmc.neutrodyne.core.data.testClassifier
import ch.lkmc.neutrodyne.core.data.testUserAgent
import ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.ChartGenre
import ch.lkmc.neutrodyne.core.model.DirectoryAccess
import ch.lkmc.neutrodyne.core.model.DirectoryKeySource
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.model.ProviderFailure
import ch.lkmc.neutrodyne.core.model.ProviderId
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.network.okhttp.AuthInterceptor
import ch.lkmc.neutrodyne.core.network.okhttp.CoreClients
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import ch.lkmc.neutrodyne.core.network.okhttp.LocalNetworkGuard
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import ch.lkmc.neutrodyne.core.network.okhttp.UserAgentInterceptor
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.assertSearchContract
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Interceptor
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import ch.lkmc.neutrodyne.core.model.settings.DiscoverSettingKeys as Keys

/** Real API Ktor-over-OkHttp; the existing island's debug interceptor routes recorded providers. */
class SearchRepositoryTest {
    private val server = MockWebServer()
    private val clock = TestClock()
    private val prefs = FakeSettingsRepository()
    private val secrets = FakeSecretStore()
    private val db = newDb()
    private lateinit var clients: OkHttpNeutrodyneHttpClients
    private lateinit var metadata: DirectoryMetadataCache
    private lateinit var settings: DirectorySettings
    private lateinit var apple: AppleSearchProvider
    private lateinit var pi: PodcastIndexSearchProvider
    private lateinit var repo: SearchRepositoryImpl
    private var route: (RecordedRequest) -> MockResponse = { request ->
        val file =
            when {
                request.target.contains("/lookup") -> "apple-lookup.json"
                request.target.contains("toppodcasts") -> "apple-genre.json"
                request.target.contains("marketingtools") -> "apple-top.json"
                request.target.contains("itunes.apple.com/search") -> "apple-search.json"
                request.target.contains("api.fyyd.de") -> "fyyd.json"
                else -> "podcast-index.json"
            }
        mockResponse(200, fixture(file), "Content-Type" to "text/javascript")
    }

    @Before fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = route(request)
            }
        server.start()
        val rewrite =
            Interceptor { chain ->
                val request = chain.request()
                if (request.url.host !in DIRECTORY_HOSTS) return@Interceptor chain.proceed(request)
                val target =
                    server
                        .url(
                            "/${request.url.host}${request.url.encodedPath}",
                        ).newBuilder()
                        .encodedQuery(request.url.encodedQuery)
                        .build()
                val builder = request.newBuilder().url(target)
                if (request.header("X-Neutrodyne-Directory-Origin") != null) {
                    builder.header("X-Neutrodyne-Directory-Origin", "http://${target.host}:${target.port}")
                }
                chain.proceed(builder.build())
            }
        val platform = fakePlatform()
        val island =
            NetworkClients(
                CoreClients(
                    UserAgentInterceptor(testUserAgent()),
                    LocalNetworkGuard(LocalNetworkAccess(), platform),
                    DnsFamilyHints(),
                    setOf(rewrite),
                ),
                AuthInterceptor {
                    secrets
                },
            )
        clients = OkHttpNeutrodyneHttpClients(island, testUserAgent())
        configure()
    }

    private fun configure(build: BuildInfo = testBuildInfo()) {
        settings = DirectorySettings(prefs, { secrets }, build, fakePlatform())
        metadata = DirectoryMetadataCache(clock, settings)
        val http = DirectoryHttp(clients)
        apple = AppleSearchProvider(http, settings, TokenBucket(clock), clock)
        pi = PodcastIndexSearchProvider(http, settings, clock)
        repo =
            SearchRepositoryImpl(
                apple,
                FyydSearchProvider(http, settings, clock),
                pi,
                settings,
                metadata,
                { secrets },
                { db },
                Dispatchers.Default,
            )
    }

    @After fun tearDown() {
        clients.close()
        server.close()
        db.close()
    }

    @Test fun recordedNewsFusesProvidersAndAnnotatesAliasesWithoutWrites() =
        runBlocking {
            val alias = "https://feeds.npr.org/500005/podcast.xml"
            val id = seedPodcast(db, feedUrl = "https://other.example/feed", feedKey = "other.example/feed")
            db.podcastDao().insertAlias(
                PodcastUrlAliasEntity(
                    checkNotNull(UrlNormalizer.forIdentity(alias)),
                    id,
                    AliasReason.SUBSCRIBE_INPUT,
                    clock.now(),
                ),
            )
            val rows = repo.search("news").assertSearchContract("news")
            assertEquals(3, rows.size)
            val npr = rows.last().hits.first { it.feedUrl == alias }
            assertEquals(setOf(ProviderId.APPLE, ProviderId.FYYD), npr.providers)
            assertEquals(id, npr.subscribedPodcastId)
            assertEquals(1, db.podcastDao().allForExport().size)
            assertEquals(ProviderStatus.Disabled, rows.last().statuses[ProviderId.PODCAST_INDEX])
            assertEquals(2, server.requestCount)
        }

    @Test fun partialAndAllProviderFailuresFinishWithoutLeakingErrors() =
        runBlocking {
            route =
                { request ->
                    if (request.target.contains(
                            "fyyd",
                        )
                    ) {
                        mockResponse(
                            500,
                            "private query or secret",
                        )
                    } else {
                        mockResponse(body = fixture("apple-search.json"))
                    }
                }
            val partial = repo.search("news").assertSearchContract("news").last()
            assertEquals(1, partial.hits.size)
            assertIs<ProviderStatus.Failed>(partial.statuses[ProviderId.FYYD])
            route = { mockResponse(503, "secret") }
            val failed = repo.search("other news").assertSearchContract("other news").last()
            assertTrue(failed.hits.isEmpty())
            assertFalse(failed.statuses.toString().contains("secret"))
        }

    @Test fun unsafeBlankAndDisabledSearchesHaveNoRequests() =
        runBlocking {
            for (query in listOf(
                "",
                "https://private.example/rss?token=secret",
                "private.example/feed",
                "@private",
                "localhost",
            )) {
                assertTrue(
                    repo
                        .search(query)
                        .toList()
                        .single()
                        .complete,
                )
            }
            prefs.set(Keys.APPLE_ENABLED, false)
            prefs.set(Keys.FYYD_ENABLED, false)
            assertTrue(
                repo
                    .search("news")
                    .toList()
                    .single()
                    .hits
                    .isEmpty(),
            )
            assertIs<Outcome.Failure<ProviderStatus>>(repo.charts(null))
            assertEquals(0, server.requestCount)
        }

    @Test fun countryCacheAndSwitchChangesNeverReplayDisabledHits() =
        runBlocking {
            repo.search("news").toList()
            repo.search(" NEWS ").toList()
            assertEquals(2, server.requestCount)
            prefs.set(Keys.COUNTRY, "US")
            repo.search("news").toList()
            assertEquals(4, server.requestCount)
            prefs.set(Keys.APPLE_ENABLED, false)
            val rows = repo.search("news").toList().last()
            assertTrue(rows.hits.all { ProviderId.APPLE !in it.providers })
            assertEquals(ProviderStatus.Disabled, rows.statuses[ProviderId.APPLE])
            assertEquals("US", settings.current().country)
        }

    @Test fun chartsCoalesceAndReorderBatchedLookupAndExpireAfterSixHours() =
        runBlocking {
            val answers = coroutineScope { (1..3).map { async { apple.charts(null, "US") } }.awaitAll() }
            assertEquals(2, server.requestCount)
            val urls = (answers.first() as Outcome.Success).value.map { it.feedUrl }
            assertEquals(listOf("https://feeds.simplecast.com/Sl5CSM3S"), urls)
            apple.charts(null, "US")
            assertEquals(2, server.requestCount)
            clock.advanceBy(361.minutes)
            apple.charts(null, "US")
            assertEquals(4, server.requestCount)
        }

    @Test fun searchLookupAndChartsShareRollingLimiterAndCooldown() =
        runBlocking {
            apple.search("news", "US")
            apple.charts(null, "US")
            repeat(17) { apple.lookup(persistentListOf((it + 100).toString()), DirectoryAccess.ENABLED_ONLY) }
            assertEquals(20, server.requestCount)
            assertEquals(
                Outcome.Failure(ProviderStatus.RateLimited),
                apple.lookup(persistentListOf("999"), DirectoryAccess.ENABLED_ONLY),
            )
            clock.advanceBy(1.minutes)
            route = { mockResponse(429, " ".repeat(2 * 1024 * 1024 + 1)) }
            assertEquals(
                Outcome.Failure(ProviderStatus.RateLimited),
                apple.lookup(persistentListOf("999"), DirectoryAccess.ENABLED_ONLY),
            )
            assertEquals(Outcome.Failure(ProviderStatus.RateLimited), apple.search("next", "US"))
            assertEquals(21, server.requestCount)
        }

    @Test fun verifyBeforeReplacementUserOverridesBuildAndClearFallsBack() =
        runBlocking {
            configure(testBuildInfo().copy(podcastIndexKey = "build", podcastIndexSecret = "build-secret"))
            val initialProvider = repo.observeProviders().first().first { it.id == ProviderId.PODCAST_INDEX }
            val source = initialProvider.keySource
            assertEquals(DirectoryKeySource.BUILD, source)
            assertIs<Outcome.Success<Unit>>(repo.setPodcastIndexKey("user", "user-secret".toCharArray()))
            assertEquals("user" to "user-secret", secrets.podcastIndexKey())
            val info = repo.observeProviders().first().first { it.id == ProviderId.PODCAST_INDEX }
            assertEquals(DirectoryKeySource.USER, info.keySource)
            route = { mockResponse(401, "bad key") }
            val rejected =
                assertIs<Outcome.Failure<ProviderStatus>>(repo.setPodcastIndexKey("bad", "bad".toCharArray()))
            assertEquals(ProviderFailure.CREDENTIALS, assertIs<ProviderStatus.Failed>(rejected.error).reason)
            assertEquals("user" to "user-secret", secrets.podcastIndexKey())
            repo.clearPodcastIndexKey()
            assertNull(secrets.podcastIndexKey())
            val fallbackProvider = repo.observeProviders().first().first { it.id == ProviderId.PODCAST_INDEX }
            val fallback = fallbackProvider.keySource
            assertEquals(DirectoryKeySource.BUILD, fallback)
        }

    @Test fun fixedClockAuthAndSingleSkewRetryNeverUseBasicAuth() =
        runBlocking {
            secrets.put("podcastindex", "key", "secret".toCharArray())
            val skew = TimeUnit.DAYS.toMillis(2)
            var attempts = 0
            route = {
                attempts++
                if (attempts ==
                    1
                ) {
                    mockResponse(
                        401,
                        "",
                        "Date" to
                            DateTimeFormatter.RFC_1123_DATE_TIME.format(
                                Instant.ofEpochMilli(clock.now() + skew).atZone(ZoneOffset.UTC),
                            ),
                    )
                } else {
                    mockResponse(body = fixture("podcast-index.json"))
                }
            }
            assertIs<Outcome.Success<IndexAnswer>>(pi.search("news", settings.current()))
            val first = server.takeRequest()
            val second = server.takeRequest()
            val date = (clock.now() / 1000).toString()
            assertEquals(date, first.headers["X-Auth-Date"])
            assertEquals(("key" + "secret" + date).encodeUtf8().sha1().hex(), first.headers["Authorization"])
            assertEquals(((clock.now() + skew) / 1000).toString(), second.headers["X-Auth-Date"])
            assertNull(first.headers["X-Neutrodyne-Directory-Origin"])
            assertEquals(2, attempts)
        }

    @Test fun revokedKeyFailsOnlyIndexAndKeyLossDisablesIt() =
        runBlocking {
            secrets.put("podcastindex", "key", "secret".toCharArray())
            route =
                { request ->
                    if (request.target.contains(
                            "podcastindex",
                        )
                    ) {
                        mockResponse(
                            401,
                            "revoked",
                        )
                    } else {
                        mockResponse(
                            body = fixture(if (request.target.contains("fyyd")) "fyyd.json" else "apple-search.json"),
                        )
                    }
                }
            val result = repo.search("news").toList().last()
            assertIs<ProviderStatus.Failed>(result.statuses[ProviderId.PODCAST_INDEX])
            assertEquals(ProviderStatus.Ok, result.statuses[ProviderId.APPLE])
            secrets.remove("podcastindex")
            assertEquals(Outcome.Failure(ProviderStatus.Disabled), pi.lookup("1"))
        }

    @Test fun configurationChangesAndCallerCancellationCancelInFlightProviders() =
        runBlocking {
            route = {
                MockResponse
                    .Builder()
                    .body(fixture("apple-search.json"))
                    .bodyDelay(30, TimeUnit.SECONDS)
                    .build()
            }
            val job = launch(Dispatchers.Default) { repo.search("news").collect() }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            prefs.set(Keys.COUNTRY, "US")
            withTimeout(5000) { job.join() }
            assertTrue(job.isCancelled)
            val caller = launch(Dispatchers.Default) { repo.search("second").collect() }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            caller.cancelAndJoin()
            assertTrue(caller.isCancelled)
        }

    @Test fun boundedJsonAndEightSecondDeadlineBecomeProviderFailures(): Unit =
        runBlocking {
            route = { mockResponse(body = " ".repeat(2 * 1024 * 1024 + 1)) }
            assertIs<Outcome.Failure<ProviderStatus>>(apple.search("large", "US"))
            route = {
                MockResponse
                    .Builder()
                    .body("{}")
                    .bodyDelay(30, TimeUnit.SECONDS)
                    .build()
            }
            assertIs<Outcome.Failure<ProviderStatus>>(apple.search("slow", "US"))
        }

    @Test fun genreNamesNormalizeNfcAndKeepStableIds() {
        assertEquals(ChartGenre.NEWS, repo.genreForGroupName("ACTUALITE\u0301S"))
        assertEquals(ChartGenre.TECHNOLOGY, repo.genreForGroupName("Technik"))
        assertNull(repo.genreForGroupName("Unknown"))
    }

    @Test fun cancelledVerificationPreservesOldPairAndIndexCacheTracksKeys() =
        runBlocking {
            secrets.put("podcastindex", "old", "old-secret".toCharArray())
            pi.search("news", settings.current())
            pi.search("news", settings.current())
            assertEquals(1, server.requestCount)
            secrets.put("podcastindex", "new", "new-secret".toCharArray())
            pi.search("news", settings.current())
            assertEquals(2, server.requestCount)
            repeat(2) { server.takeRequest() }
            route = {
                MockResponse
                    .Builder()
                    .body(fixture("podcast-index.json"))
                    .bodyDelay(30, TimeUnit.SECONDS)
                    .build()
            }
            val job = launch(Dispatchers.Default) { repo.setPodcastIndexKey("proposed", "secret".toCharArray()) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertEquals("new" to "new-secret", secrets.podcastIndexKey())
        }

    @Test fun directoryAuthDoesNotFollowKtorRedirects() =
        runBlocking {
            val other = MockWebServer()
            other.start()
            try {
                val target = other.url("/lookup")
                secrets.put("http://${target.host}:${target.port}", "publisher", "password".toCharArray())
                other.enqueue(mockResponse(body = fixture("podcast-index.json")))
                route = { mockResponse(302, "", "Location" to target.toString()) }
                // HTTP→HTTP exercises Ktor's redirect path; an HTTPS→HTTP API redirect is refused.
                DirectoryHttp(clients).get(
                    server.url("/lookup").toString(),
                    headers =
                        mapOf(
                            "X-Auth-Key" to "key",
                            "X-Auth-Date" to "123",
                            "Authorization" to "sha1",
                        ),
                )
                val redirected = checkNotNull(other.takeRequest(5, TimeUnit.SECONDS))
                assertNull(redirected.headers["X-Auth-Key"])
                assertNull(redirected.headers["X-Auth-Date"])
                assertNull(redirected.headers["Authorization"])
            } finally {
                other.close()
            }
        }

    @Test fun annotationsSearchExistingIndexesAndGenreIdsMatchLiveTaxonomy() =
        runBlocking {
            val plan =
                db.useReaderConnection { connection ->
                    connection.usePrepared(
                        "EXPLAIN QUERY PLAN SELECT feedKey AS url, id AS podcastId FROM podcast WHERE feedKey IN (?) " +
                            "UNION ALL SELECT url, podcastId FROM podcast_url_alias WHERE url IN (?)",
                    ) { statement ->
                        statement.bindText(1, "a.example/feed")
                        statement.bindText(2, "a.example/feed")
                        buildList { while (statement.step()) add(statement.getText(3)) }
                    }
                }
            assertTrue(plan.any { "index_podcast_feedKey" in it }, plan.toString())
            assertTrue(plan.any { "sqlite_autoindex_podcast_url_alias" in it }, plan.toString())
            val ids = Json.parseToJsonElement(fixture("apple-genres.json")).jsonObject.keys
            assertEquals(ids, ChartGenre.entries.map { it.id }.toSet())
        }

    @Test fun directoryWorkLeavesCollectorThread() =
        runBlocking {
            val caller = Thread.currentThread()
            val onCaller = ConcurrentLinkedQueue<Boolean>()
            val observedClock =
                object : Clock {
                    override fun now(): Long = clock.now()

                    override fun elapsedRealtime(): Long {
                        onCaller.add(Thread.currentThread() == caller)
                        return clock.elapsedRealtime()
                    }
                }
            val observedMetadata = DirectoryMetadataCache(observedClock, settings)
            val service =
                SearchRepositoryImpl(
                    apple,
                    FyydSearchProvider(DirectoryHttp(clients), settings, clock),
                    pi,
                    settings,
                    observedMetadata,
                    { secrets },
                    { db },
                    Dispatchers.Default,
                )
            service.search("news").assertSearchContract("news")
            assertTrue(onCaller.isNotEmpty())
            assertTrue(onCaller.none { it }, "Directory aggregation ran on its collector thread")
        }

    @Test fun failedKeyStorageReturnsFailureWithoutReplacingOldPair() =
        runBlocking {
            secrets.put("podcastindex", "old", "old-secret".toCharArray())
            val failing =
                object : SecretStore by secrets {
                    override suspend fun put(
                        origin: String,
                        username: String,
                        secret: CharArray,
                        attributes: Map<String, String>,
                    ): Long? = throw IOException("secret details must not escape")
                }
            val service =
                SearchRepositoryImpl(
                    apple,
                    FyydSearchProvider(DirectoryHttp(clients), settings, clock),
                    pi,
                    settings,
                    metadata,
                    { failing },
                    { db },
                    Dispatchers.Default,
                )
            assertEquals(
                Outcome.Failure(ProviderStatus.Failed(NetError.Other("storage"))),
                service.setPodcastIndexKey("proposed", "secret".toCharArray()),
            )
            assertEquals("old" to "old-secret", secrets.podcastIndexKey())
        }

    private companion object {
        val DIRECTORY_HOSTS =
            setOf("itunes.apple.com", "rss.marketingtools.apple.com", "api.fyyd.de", "api.podcastindex.org")
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/discovery/$name")).bufferedReader().use {
            it.readText()
        }
}
