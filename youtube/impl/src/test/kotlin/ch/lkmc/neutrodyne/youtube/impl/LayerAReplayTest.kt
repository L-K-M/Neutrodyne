// SPDX-License-Identifier: Unlicense
@file:Suppress("ktlint:standard:max-line-length")

package ch.lkmc.neutrodyne.youtube.impl

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.UserAgentProvider
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.network.okhttp.AuthInterceptor
import ch.lkmc.neutrodyne.core.network.okhttp.CoreClients
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import ch.lkmc.neutrodyne.core.network.okhttp.LocalNetworkGuard
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import ch.lkmc.neutrodyne.core.network.okhttp.UserAgentInterceptor
import ch.lkmc.neutrodyne.core.testing.FakeExtractorChannelLookup
import ch.lkmc.neutrodyne.core.testing.FakePlatformInfo
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeFeedMetadataReader
import ch.lkmc.neutrodyne.youtube.api.ChannelResolution
import ch.lkmc.neutrodyne.youtube.api.ExtractorChannelLookup
import ch.lkmc.neutrodyne.youtube.api.FailReason
import ch.lkmc.neutrodyne.youtube.api.FeedChannelMetadata
import ch.lkmc.neutrodyne.youtube.api.MetadataDepth
import ch.lkmc.neutrodyne.youtube.api.ResolvedVia
import ch.lkmc.neutrodyne.youtube.api.YtRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The same corpus and real app-client stack run on desktop and Android's host JVM. */
class LayerAReplayTest {
    private val id = "UCBJycsmduvYEL83R_U4JriQ"
    private val other = "UCLA_DiR1FfKNvjuUpBHmylQ"
    private val video = "3iRUwVzRDZQ"
    private lateinit var server: MockWebServer
    private lateinit var clients: OkHttpNeutrodyneHttpClients
    private lateinit var network: NetworkClients
    private lateinit var core: CoreClients
    private val kinds = Collections.synchronizedList(mutableListOf<HttpClientKind>())
    private val extractor = FakeExtractorChannelLookup()
    private val reader = FakeYouTubeFeedMetadataReader()

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val platform = FakePlatformInfo()
        val ua = UserAgentProvider(platform, "0.1.0", "https://example.com/repo")
        // Production URLs stay HTTPS. Only this test interceptor changes their socket destination.
        val route =
            Interceptor { chain ->
                val original = chain.request()
                check(original.url.host == "www.youtube.com") { "Unexpected host: ${original.url.host}" }
                val local =
                    server
                        .url(
                            original.url.encodedPath,
                        ).newBuilder()
                        .encodedQuery(original.url.encodedQuery)
                        .build()
                chain
                    .proceed(original.newBuilder().url(local).build())
                    .newBuilder()
                    .request(original)
                    .build()
            }
        core =
            CoreClients(
                UserAgentInterceptor(ua),
                LocalNetworkGuard(LocalNetworkAccess(), platform),
                DnsFamilyHints(),
                setOf(route),
            )
        network = NetworkClients(core, AuthInterceptor { CredentialLookup.None })
        clients = OkHttpNeutrodyneHttpClients(network, ua)
    }

    @After fun tearDown() {
        clients.close()
        core.evict()
        network.base.dispatcher.executorService
            .shutdown()
        server.close()
    }

    private fun resolver(lookup: ExtractorChannelLookup = extractor): DefaultYouTubeChannelResolver =
        DefaultYouTubeChannelResolver(
            object : ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients {
                override fun client(kind: HttpClientKind): io.ktor.client.HttpClient {
                    kinds += kind
                    return clients.client(kind)
                }
            },
            reader,
            lookup,
            { "de-CH" },
        )

    @Test fun knownIdentityNeedsNoNetworkAtIdOnlyAndSurvivesMetadataFailure() =
        runBlocking {
            val resolver = resolver()
            assertEquals(ResolvedVia.STATIC, resolved(resolver.resolve(YtRef.Channel(id), MetadataDepth.ID_ONLY)).via)
            assertEquals(0, server.requestCount)
            response(404)
            val channel = resolved(resolver.resolve(YtRef.Channel(id), MetadataDepth.AVATAR))
            assertEquals(id, channel.channelId)
            assertNull(channel.avatarUrl)
            assertEquals(1, server.requestCount)
        }

    @Test fun pageUsesFeedClientConsentCookieAppLocaleAndOwnUserAgent() =
        runBlocking {
            response(body = fixture("channel-head.html"))
            val channel = resolved(resolver().resolve(YtRef.Handle("@mkbhd")))
            assertEquals(id, channel.channelId)
            assertEquals("MKBHD & Studio", channel.title)
            assertEquals(listOf(HttpClientKind.FEED), kinds)
            val request = server.takeRequest()
            assertEquals("/@mkbhd", request.url.encodedPath)
            assertEquals("SOCS=CAE=", request.headers["Cookie"])
            assertEquals("de-CH, en;q=0.5", request.headers["Accept-Language"])
            assertEquals("text/html", request.headers["Accept"])
            assertTrue(request.headers["User-Agent"]!!.startsWith("Neutrodyne/"))
        }

    @Test fun unicodeHandleIsEncodedOnlyInRequest() =
        runBlocking {
            response(body = fixture("canonical-head.html"))
            assertEquals(id, resolved(resolver().resolve(YtRef.Handle("@café.音楽"), MetadataDepth.ID_ONLY)).channelId)
            assertEquals("/@caf%C3%A9.%E9%9F%B3%E6%A5%BD", server.takeRequest().url.encodedPath)
        }

    @Test fun userFeedPrecedesExtractorAndPageAndUsesFirstEntryPort() =
        runBlocking {
            val bytes = "<feed>port parses this</feed>"
            response(body = bytes)
            reader.read = { FeedChannelMetadata(id, "Feed author", "https://www.youtube.com/channel/$other", 1) }
            val channel = resolved(resolver().resolve(YtRef.LegacyPath("user/legacy"), MetadataDepth.ID_ONLY))
            assertEquals(id, channel.channelId)
            assertEquals("Feed author", channel.title)
            assertEquals(ResolvedVia.USER_FEED, channel.via)
            assertEquals(bytes, reader.bodies.single().decodeToString())
            assertEquals("legacy", server.takeRequest().url.queryParameter("user"))
            assertTrue(extractor.calls.isEmpty())
        }

    @Test fun userFeedAuthorUriAndEmptyOr404Fallback() =
        runBlocking {
            reader.read = { FeedChannelMetadata(null, "Feed name", "https://www.youtube.com/channel/$id", 1) }
            response(body = "atom")
            assertEquals(id, resolved(resolver().resolve(YtRef.LegacyPath("user/x"), MetadataDepth.ID_ONLY)).channelId)
            reader.read = { FeedChannelMetadata(id, "Empty", null, 0) }
            response(body = "empty")
            response(body = fixture("canonical-head.html"))
            assertEquals(
                ResolvedVia.HTML,
                resolved(resolver().resolve(YtRef.LegacyPath("user/x"), MetadataDepth.ID_ONLY)).via,
            )
            response(404)
            response(body = fixture("canonical-head.html"))
            assertEquals(id, resolved(resolver().resolve(YtRef.LegacyPath("user/x"), MetadataDepth.ID_ONLY)).channelId)
            assertEquals(2, extractor.calls.size)
        }

    @Test fun userFeedFailuresDoNotMaskRateLimitOrConsent() =
        runBlocking {
            response(429)
            assertEquals(FailReason.RATE_LIMITED, failure(resolver().resolve(YtRef.LegacyPath("user/x"))).reason)
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(302)
                    .addHeader("Location", "https://consent.youtube.com/m")
                    .build(),
            )
            assertEquals(FailReason.CONSENT_WALL, failure(resolver().resolve(YtRef.LegacyPath("user/x"))).reason)
            assertTrue(extractor.calls.isEmpty())
            assertEquals(0, reader.bodies.size)
        }

    @Test fun extractorResolvedAndNotFoundAreAuthoritative() =
        runBlocking {
            extractor.result = ChannelResolution.Resolved(id, "Engine", null, null, null, ResolvedVia.EXTRACTOR)
            assertEquals("Engine", resolved(resolver().resolve(YtRef.Handle("@x"))).title)
            extractor.result = ChannelResolution.NotFound
            assertEquals(ChannelResolution.NotFound, resolver().resolve(YtRef.Handle("@x")))
            assertEquals(0, server.requestCount)
        }

    @Test fun extractorNullFailureAndExceptionFallBackToHtml() =
        runBlocking {
            for (result in listOf(null, ChannelResolution.Failed(true, FailReason.NETWORK, null))) {
                extractor.result = result
                response(body = fixture("canonical-head.html"))
                assertEquals(id, resolved(resolver().resolve(YtRef.Handle("@x"))).channelId)
            }
            response(body = fixture("canonical-head.html"))
            val throws =
                object : ExtractorChannelLookup {
                    override suspend fun lookup(
                        ref: YtRef,
                        depth: MetadataDepth,
                    ): ChannelResolution? = error("host unavailable")
                }
            assertEquals(id, resolved(resolver(throws).resolve(YtRef.Handle("@x"))).channelId)
        }

    @Test fun oembedUsesApiThenAuthorPageAndNameFallback() =
        runBlocking {
            response(body = fixture("video-oembed.json"))
            response(body = "<head><meta itemprop=identifier content=$id></head>")
            val channel = resolved(resolver().resolve(YtRef.Video(video)))
            assertEquals(id, channel.channelId)
            assertEquals("MKBHD", channel.title)
            assertEquals(ResolvedVia.OEMBED, channel.via)
            assertEquals(listOf(HttpClientKind.API, HttpClientKind.FEED), kinds)
            val request = server.takeRequest()
            assertEquals("/oembed", request.url.encodedPath)
            assertEquals("https://www.youtube.com/watch?v=$video", request.url.queryParameter("url"))
            assertEquals("json", request.url.queryParameter("format"))
            assertEquals("/@mkbhd", server.takeRequest().url.encodedPath)
        }

    @Test fun oembedChannelAuthorResolvesWithoutSecondRequestAtIdOnly() =
        runBlocking {
            response(body = """{"author_url":"https://www.youtube.com/channel/$id","author_name":"Author"}""")
            assertEquals(id, resolved(resolver().resolve(YtRef.Video(video), MetadataDepth.ID_ONLY)).channelId)
            assertEquals(1, server.requestCount)
        }

    @Test fun oembedEmbeddingDisabledUsesWatchIdentityThenChannelMetadata() =
        runBlocking {
            for (code in listOf(401, 403, 404)) {
                response(code)
                response(body = fixture("watch-head.html"))
                response(body = fixture("channel-head.html"))
                val channel = resolved(resolver().resolve(YtRef.Video(video)))
                assertEquals("MKBHD & Studio", channel.title)
                assertEquals("/oembed", server.takeRequest().url.encodedPath)
                assertEquals("/watch", server.takeRequest().url.encodedPath)
                assertEquals("/channel/$id", server.takeRequest().url.encodedPath)
            }
        }

    @Test fun oembedOtherFailuresNeverRequestWatchAndRejectLoops() =
        runBlocking {
            for (body in listOf(
                "not json",
                "[]",
                """{"author_url":"https://www.youtube.com/watch?v=$video"}""",
                """{"author_url":"https://evil.example/@author"}""",
                """{"author_url":"@bare"}""",
            )) {
                response(body = body)
                assertEquals(FailReason.PAGE_UNREADABLE, failure(resolver().resolve(YtRef.Video(video))).reason)
            }
            for (code in listOf(429, 500)) {
                response(code)
                assertTrue(failure(resolver().resolve(YtRef.Video(video))).retryable)
            }
            assertEquals(7, server.requestCount)
            assertTrue(extractor.calls.isEmpty())
        }

    @Test fun playlistOffersOnlyFeedAuthorAndDoesNotFollowVideos() =
        runBlocking {
            reader.read = { FeedChannelMetadata(other, "Owner", "https://www.youtube.com/channel/$id", 15) }
            response(body = "playlist atom")
            val result =
                assertIs<ChannelResolution.PlaylistUnsupported>(resolver().resolve(YtRef.Playlist("PL01234567890")))
            assertEquals(id, result.ownerChannelId)
            assertEquals("Owner", result.ownerTitle)
            response(404)
            assertNull(
                assertIs<ChannelResolution.PlaylistUnsupported>(
                    resolver().resolve(YtRef.Playlist("PL01234567890")),
                ).ownerChannelId,
            )
            assertTrue(extractor.calls.isEmpty())
        }

    @Test fun htmlStatusMappingAndUnusablePage() =
        runBlocking {
            val resolver = resolver()
            response(404)
            assertEquals(ChannelResolution.NotFound, resolver.resolve(YtRef.Handle("@gone")))
            for ((code, reason) in listOf(
                429 to FailReason.RATE_LIMITED,
                500 to FailReason.NETWORK,
                503 to FailReason.NETWORK,
                403 to FailReason.PAGE_UNREADABLE,
            )) {
                response(code)
                assertEquals(reason, failure(resolver.resolve(YtRef.Handle("@x"))).reason)
            }
            response(body = fixture("consent-head.html"))
            assertEquals(FailReason.PAGE_UNREADABLE, failure(resolver.resolve(YtRef.Handle("@x"))).reason)
        }

    @Test fun consentRedirectAndRedirectLoopsAreBounded() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(303)
                    .addHeader("Location", "https://consent.youtube.com/m?continue=x")
                    .build(),
            )
            assertEquals(FailReason.CONSENT_WALL, failure(resolver().resolve(YtRef.Handle("@x"))).reason)
            repeat(2) {
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(302)
                        .addHeader("Location", "https://www.youtube.com/@x")
                        .build(),
                )
            }
            assertEquals(FailReason.NETWORK, failure(resolver().resolve(YtRef.Handle("@loop"))).reason)
            assertEquals(3, server.requestCount)
        }

    @Test fun relativeRedirectWorksAndForeignOrDowngradeRedirectIsRejected() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(302)
                    .addHeader("Location", "/channel/$id")
                    .build(),
            )
            response(body = fixture("canonical-head.html"))
            assertEquals(id, resolved(resolver().resolve(YtRef.Handle("@x"))).channelId)
            for (location in listOf("https://evil.example/", "http://www.youtube.com/@x")) {
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(302)
                        .addHeader("Location", location)
                        .build(),
                )
                assertEquals(FailReason.PAGE_UNREADABLE, failure(resolver().resolve(YtRef.Handle("@x"))).reason)
            }
            assertEquals(4, server.requestCount)
        }

    @Test fun oversizedFeedAndOembedAreRejectedBeforeParsing() =
        runBlocking {
            response(body = " ".repeat(YouTubeHttp.FEED_CAP + 1))
            assertEquals(FailReason.PAGE_UNREADABLE, failure(resolver().resolve(YtRef.LegacyPath("user/x"))).reason)
            assertTrue(reader.bodies.isEmpty())
            response(body = " ".repeat(64 * 1024 + 1))
            assertEquals(FailReason.PAGE_UNREADABLE, failure(resolver().resolve(YtRef.Video(video))).reason)
        }

    @Test fun chunkedHeadClosesWithoutWaitingForSlowBodyAndNextRequestWorks() =
        runBlocking {
            val body = fixture("canonical-head.html") + " ".repeat(128 * 1024)
            server.enqueue(
                MockResponse
                    .Builder()
                    .body(body)
                    .throttleBody(8192, 10, TimeUnit.SECONDS)
                    .build(),
            )
            withTimeout(5000) {
                assertEquals(id, resolved(resolver().resolve(YtRef.Handle("@x"), MetadataDepth.ID_ONLY)).channelId)
            }
            response(body = fixture("channel-head.html"))
            withTimeout(5000) { assertEquals(id, resolved(resolver().resolve(YtRef.Handle("@y"))).channelId) }
        }

    @Test fun cancellationPropagatesFromExtractorAndNetworkAndDoesNotFallback() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val waiting =
                object : ExtractorChannelLookup {
                    override suspend fun lookup(
                        ref: YtRef,
                        depth: MetadataDepth,
                    ): ChannelResolution? {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
            val engine = async { resolver(waiting).resolve(YtRef.Handle("@x")) }
            started.await()
            engine.cancelAndJoin()
            assertEquals(0, server.requestCount)
            server.enqueue(
                MockResponse
                    .Builder()
                    .body("<head>")
                    .headersDelay(30, TimeUnit.SECONDS)
                    .build(),
            )
            val read = async { resolver().resolve(YtRef.Handle("@x")) }
            kotlinx.coroutines.withContext(
                kotlinx.coroutines.Dispatchers.IO,
            ) { server.takeRequest(5, TimeUnit.SECONDS) }
            read.cancelAndJoin()
            assertTrue(read.isCancelled)
        }

    @Test fun overallBudgetIncludesExtractorAndPreservesKnownIdentity() =
        runTest {
            val waiting =
                object : ExtractorChannelLookup {
                    override suspend fun lookup(
                        ref: YtRef,
                        depth: MetadataDepth,
                    ): ChannelResolution? = awaitCancellation()
                }
            val start = testScheduler.currentTime
            assertEquals(FailReason.NETWORK, failure(resolver(waiting).resolve(YtRef.Handle("@x"))).reason)
            assertEquals(20_000L, testScheduler.currentTime - start)
            assertEquals(id, resolved(resolver(waiting).resolve(YtRef.Channel(id))).channelId)
            assertEquals(40_000L, testScheduler.currentTime - start)
            assertEquals(0, server.requestCount)
        }

    @Test fun queryIsRejectedBeforeIo() =
        runBlocking {
            assertFailsWith<IllegalArgumentException> { resolver().resolve(YtRef.Query("query")) }
            assertEquals(0, server.requestCount)
        }

    private fun response(
        code: Int = 200,
        body: String = "",
    ) = server.enqueue(
        MockResponse
            .Builder()
            .code(code)
            .body(body)
            .build(),
    )

    private fun fixture(name: String) = checkNotNull(javaClass.getResource("/recorded/$name")).readText()

    private fun resolved(result: ChannelResolution) = assertIs<ChannelResolution.Resolved>(result)

    private fun failure(result: ChannelResolution) = assertIs<ChannelResolution.Failed>(result)
}
