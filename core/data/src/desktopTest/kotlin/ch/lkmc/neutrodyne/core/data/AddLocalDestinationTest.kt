// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.data.add.AddPodcastResolverImpl
import ch.lkmc.neutrodyne.core.data.add.PreviewCache
import ch.lkmc.neutrodyne.core.data.discovery.DirectoryMetadataCache
import ch.lkmc.neutrodyne.core.data.discovery.DirectorySettings
import ch.lkmc.neutrodyne.core.data.fetch.FeedFetcher
import ch.lkmc.neutrodyne.core.data.fetch.FeedTempFiles
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.AddResolution
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupAutodiscovery
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupShowNotesSanitizer
import ch.lkmc.neutrodyne.feeds.jvm.parse.XmlPullFeedParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import org.junit.After
import org.junit.Test
import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 03 implementation note 2026-11-02: the add pipeline's automatic fetches never reach a local
 * destination — a shared page cannot turn alternates, candidate redirects or its own 302 into a
 * LAN request — while a *typed* LAN URL still fetches (PLAN's LAN rule is Android-only). The
 * recording transport answers "public" hosts and LAN literals alike, so the request log proves
 * zero requests reached a refused destination.
 */
class AddLocalDestinationTest {
    private val clock = TestClock()
    private val db = newDb(clock)
    private val root =
        kotlin.io.path
            .createTempDirectory("nd-local")
            .toFile()
    private val requestedUrls = Collections.synchronizedList(mutableListOf<String>())
    private var client: HttpClient? = null

    @After fun cleanup() {
        client?.close()
        db.close()
        root.deleteRecursively()
    }

    /** The production resolver over a recording [MockEngine]; [route] answers per request. */
    private fun resolver(route: (HttpRequestData) -> HttpResponseData): AddPodcastResolverImpl {
        val engine =
            MockEngine { request ->
                requestedUrls += request.url.toString()
                route(request)
            }
        // The fetcher's own loop follows 3xx; a redirecting client would hide the hop checks.
        val http = HttpClient(engine) { followRedirects = false }
        client = http
        val temps = FeedTempFiles(storagePathsFor(root), FileSystem.SYSTEM)
        val fetcher =
            FeedFetcher(
                httpClients =
                    object : NeutrodyneHttpClients {
                        override fun client(kind: HttpClientKind): HttpClient = http
                    },
                tempFiles = temps,
                fileSystem = FileSystem.SYSTEM,
                classifier = testClassifier,
                credentialLookup = CredentialLookup.None,
                clock = clock,
            )
        val secrets = FakeSecretStore()
        return AddPodcastResolverImpl(
            fetcher = fetcher,
            parser = XmlPullFeedParser.discovered(),
            sanitizer = JsoupShowNotesSanitizer(),
            tempFiles = temps,
            fileSystem = FileSystem.SYSTEM,
            cache = PreviewCache(clock),
            db = db,
            clock = clock,
            apple = unusedApple,
            podcastIndex = unusedPodcastIndex,
            autodiscovery = JsoupAutodiscovery(),
            metadata =
                DirectoryMetadataCache(
                    clock,
                    DirectorySettings(FakeSettingsRepository(), { secrets }, testBuildInfo(), fakePlatform()),
                ),
            io = Dispatchers.IO,
        )
    }

    @Test fun pageAlternateLinkToLanIsNeverFetched() =
        runBlocking {
            val resolver =
                resolver { request ->
                    when (request.url.host) {
                        "page.example" ->
                            respond(
                                "<html><head><link rel='alternate' type='application/rss+xml' " +
                                    "href='http://192.168.1.1/action'></head></html>",
                                headers = headersOf(HttpHeaders.ContentType, "text/html"),
                            )

                        "192.168.1.1" ->
                            respond(rssBody(title = "Router feed", items = arrayOf(rssItem("e1"))))

                        else -> respond("no", HttpStatusCode.NotFound)
                    }
                }
            assertEquals(
                AddPodcastError.NotAFeed,
                assertIs<AddResolution.Failure>(resolver.resolve("https://page.example/p")).error,
            )
            assertFalse(requestedUrls.any { it.startsWith("http://192.168.1.1") })
        }

    @Test fun publicAlternateCannotRedirectToLoopback() =
        runBlocking {
            val resolver =
                resolver { request ->
                    when (request.url.host) {
                        "page.example" ->
                            respond(
                                "<html><head><link rel='alternate' type='application/rss+xml' " +
                                    "href='https://feeds.example/feed'></head></html>",
                                headers = headersOf(HttpHeaders.ContentType, "text/html"),
                            )

                        "feeds.example" ->
                            respond(
                                "",
                                HttpStatusCode.Found,
                                headersOf(HttpHeaders.Location, "http://127.0.0.1:8080/"),
                            )

                        "127.0.0.1" -> respond(rssBody())

                        else -> respond("no", HttpStatusCode.NotFound)
                    }
                }
            assertEquals(
                AddPodcastError.NotAFeed,
                assertIs<AddResolution.Failure>(resolver.resolve("https://page.example/p")).error,
            )
            assertTrue(requestedUrls.any { it.startsWith("https://feeds.example") })
            assertFalse(requestedUrls.any { it.startsWith("http://127.0.0.1") })
        }

    @Test fun publicPageCannotRedirectToLocal() =
        runBlocking {
            val resolver =
                resolver { request ->
                    when (request.url.host) {
                        "page.example" ->
                            respond(
                                "",
                                HttpStatusCode.Found,
                                headersOf(HttpHeaders.Location, "http://192.168.1.1/feed"),
                            )

                        "192.168.1.1" -> respond(rssBody())

                        else -> respond("no", HttpStatusCode.NotFound)
                    }
                }
            assertEquals(
                AddPodcastError.Network(NetError.LocalNetworkUnsupported),
                assertIs<AddResolution.Failure>(resolver.resolve("https://page.example/p")).error,
            )
            assertFalse(requestedUrls.any { it.startsWith("http://192.168.1.1") })
        }

    @Test fun typedLanUrlIsStillFetched() =
        runBlocking {
            val resolver =
                resolver { request ->
                    when (request.url.host) {
                        "127.0.0.1" ->
                            respond(rssBody(title = "LAN show", items = arrayOf(rssItem("e1"))))

                        else -> respond("no", HttpStatusCode.NotFound)
                    }
                }
            assertEquals(
                "LAN show",
                assertIs<AddResolution.Feed>(resolver.resolve("http://127.0.0.1:9/feed")).preview.title,
            )
            assertTrue(requestedUrls.any { it.startsWith("http://127.0.0.1") })
        }
}
