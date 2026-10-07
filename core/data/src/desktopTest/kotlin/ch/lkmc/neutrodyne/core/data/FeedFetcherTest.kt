// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.data.fetch.FeedFetcher
import ch.lkmc.neutrodyne.core.data.fetch.FeedRequest
import ch.lkmc.neutrodyne.core.data.fetch.FeedTempFiles
import ch.lkmc.neutrodyne.core.data.fetch.FetchOutcome
import ch.lkmc.neutrodyne.core.data.fetch.MAX_REDIRECT_FOLLOWUPS
import ch.lkmc.neutrodyne.core.data.fetch.Sniff
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.testing.TestClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.Headers.Companion.headersOf
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Sink
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.zip.GZIPOutputStream

/**
 * The M1a rows of 03's `FeedFetcherTest` (Testing): validators verbatim, the manual redirect chain
 * with permanent-URL tracking, response-code policy and error kinds, Basic challenges, `Retry-After`,
 * sniffing, the body cap, gzip and credential forwarding — all against MockWebServer 5.5.0 through the
 * real island client stack (S12 shape). The socket-close on cancel is covered by S12's engine-level
 * test; the remaining fetch rows land with M1b.
 */
class FeedFetcherTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    private lateinit var root: File

    private val clock = TestClock()

    private fun setUpRoot() {
        if (!::root.isInitialized) {
            root =
                kotlin.io.path
                    .createTempDirectory("nd-fetch-test")
                    .toFile()
        }
    }

    @After
    fun tearDown() {
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun fetcher(credentials: CredentialLookup = CredentialLookup.None): FetcherBundle {
        setUpRoot()
        return newFetcher(root, credentials, clock)
    }

    private fun request(url: String): FeedRequest =
        FeedRequest(url, etag = null, lastModified = null, conditional = false)

    // --- Validators ---------------------------------------------------------------------------------

    @Test
    fun `conditional get sends stored validators verbatim`() =
        runBlocking {
            server.enqueue(mockResponse(code = 304, body = ""))
            fetcher().use { bundle ->
                bundle.fetcher.fetch(
                    FeedRequest(
                        url = server.url("/feed.xml").toString(),
                        etag = "W/\"weak-7\"",
                        lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
                        conditional = true,
                    ),
                )
            }
            val recorded = server.takeRequest()
            // Weak ETags travel verbatim — no re-quoting, no dropping (03 Validators).
            assertThat(recorded.headers["If-None-Match"]).isEqualTo("W/\"weak-7\"")
            assertThat(recorded.headers["If-Modified-Since"])
                .isEqualTo("Wed, 21 Oct 2015 07:28:00 GMT")
            assertThat(recorded.headers["Accept"]).isEqualTo(FEED_ACCEPT_VALUE)
        }

    @Test
    fun `unconditional request sends no validators`() =
        runBlocking {
            server.enqueue(mockResponse(body = rssBody()))
            fetcher().use { bundle ->
                bundle.fetcher.fetch(
                    FeedRequest(
                        url = server.url("/feed.xml").toString(),
                        etag = "\"v1\"",
                        lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
                        conditional = false,
                    ),
                )
            }
            val recorded = server.takeRequest()
            assertThat(recorded.headers["If-None-Match"]).isNull()
            assertThat(recorded.headers["If-Modified-Since"]).isNull()
        }

    @Test
    fun `validators ride every hop of the chain`() =
        runBlocking {
            server.enqueue(mockResponse(302, "", "Location" to "/moved"))
            server.enqueue(mockResponse(code = 304, body = ""))
            fetcher().use { bundle ->
                bundle.fetcher.fetch(
                    FeedRequest(
                        url = server.url("/feed.xml").toString(),
                        etag = "\"e\"",
                        lastModified = "lm",
                        conditional = true,
                    ),
                )
            }
            server.takeRequest()
            val hopTwo = server.takeRequest()
            assertThat(hopTwo.headers["If-None-Match"]).isEqualTo("\"e\"")
            assertThat(hopTwo.headers["If-Modified-Since"]).isEqualTo("lm")
        }

    // --- Status handling ----------------------------------------------------------------------------

    @Test
    fun `304 returns NotModified with echoed validator metadata`() =
        runBlocking {
            server.enqueue(
                mockResponse(
                    304,
                    "",
                    "ETag" to "\"new\"",
                    "Last-Modified" to "Thu, 01 Oct 2026 00:00:00 GMT",
                    "Cache-Control" to "max-age=1800",
                    "Date" to "Fri, 02 Oct 2026 12:00:00 GMT",
                ),
            )
            val outcome =
                fetcher().use { bundle ->
                    bundle.fetcher.fetch(
                        FeedRequest(
                            url = server.url("/feed.xml").toString(),
                            etag = "\"old\"",
                            lastModified = null,
                            conditional = true,
                        ),
                    )
                }
            outcome as FetchOutcome.NotModified
            assertThat(outcome.etag).isEqualTo("\"new\"")
            assertThat(outcome.lastModified).isEqualTo("Thu, 01 Oct 2026 00:00:00 GMT")
            assertThat(outcome.maxAgeSec).isEqualTo(1800L)
            assertThat(outcome.serverDateMs).isNotNull()
        }

    @Test
    fun `200 streams body to temp file with sha256 and sniff`() =
        runBlocking {
            val body = rssBody(items = arrayOf(rssItem("g1")))
            server.enqueue(
                mockResponse(
                    200,
                    body,
                    "ETag" to "\"v9\"",
                    "Last-Modified" to "Thu, 01 Oct 2026 00:00:00 GMT",
                    "Content-Type" to "application/rss+xml; charset=utf-8",
                ),
            )
            val outcome =
                fetcher().use { bundle ->
                    bundle.fetcher.fetch(request(server.url("/feed.xml").toString()))
                }
            outcome as FetchOutcome.Body
            assertThat(outcome.sniff).isEqualTo(Sniff.RSS)
            assertThat(outcome.etag).isEqualTo("\"v9\"")
            assertThat(outcome.charset).isEqualTo("utf-8")
            assertThat(outcome.hops).isEmpty()
            assertThat(outcome.permanentUrl).isNull()
            // SHA-256 over the raw bytes, lowercase hex (03 Body, hashing and sniffing).
            val expected = body.encodeUtf8().sha256().hex()
            assertThat(outcome.sha256Hex).isEqualTo(expected)
            FileSystem.SYSTEM.read(outcome.file) {
                assertThat(readUtf8()).isEqualTo(body)
            }
            FileSystem.SYSTEM.delete(outcome.file)
        }

    @Test
    fun `html 200 is a Body with HTML sniff — the adapter decides NOT_A_FEED`() =
        runBlocking {
            server.enqueue(mockResponse(body = "<html><body>oops</body></html>"))
            val outcome =
                fetcher().use { bundle ->
                    bundle.fetcher.fetch(request(server.url("/feed.xml").toString()))
                }
            outcome as FetchOutcome.Body
            assertThat(outcome.sniff).isEqualTo(Sniff.HTML)
            FileSystem.SYSTEM.delete(outcome.file)
        }

    @Test
    fun `opml and json bodies are sniffed not parsed`() =
        runBlocking {
            server.enqueue(mockResponse(body = "<opml version=\"1.0\"></opml>"))
            server.enqueue(mockResponse(body = "{\"feed\": true}"))
            fetcher().use { bundle ->
                val opml = bundle.fetcher.fetch(request(server.url("/a").toString()))
                val json = bundle.fetcher.fetch(request(server.url("/b").toString()))
                assertThat((opml as FetchOutcome.Body).sniff).isEqualTo(Sniff.OPML)
                assertThat((json as FetchOutcome.Body).sniff).isEqualTo(Sniff.JSON)
                FileSystem.SYSTEM.delete(opml.file)
                FileSystem.SYSTEM.delete(json.file)
            }
        }

    @Test
    fun `status codes map to Http outcomes`() =
        runBlocking {
            server.enqueue(mockResponse(code = 404, body = "no"))
            server.enqueue(mockResponse(code = 410, body = ""))
            server.enqueue(mockResponse(code = 503, body = "busy"))
            fetcher().use { bundle ->
                val notFound = bundle.fetcher.fetch(request(server.url("/404").toString()))
                val gone = bundle.fetcher.fetch(request(server.url("/410").toString()))
                val unavailable = bundle.fetcher.fetch(request(server.url("/503").toString()))
                assertThat((notFound as FetchOutcome.Http).code).isEqualTo(404)
                assertThat((gone as FetchOutcome.Http).code).isEqualTo(410)
                assertThat((unavailable as FetchOutcome.Http).code).isEqualTo(503)
            }
        }

    @Test
    fun `an unsolicited 304 without sent validators is an Http outcome`() =
        runBlocking {
            server.enqueue(mockResponse(code = 304, body = ""))
            val outcome =
                fetcher().use { bundle ->
                    bundle.fetcher.fetch(
                        FeedRequest(
                            url = server.url("/feed.xml").toString(),
                            etag = null,
                            lastModified = null,
                            conditional = true,
                        ),
                    )
                }
            // No validators went out — a bare 304 cannot mean NotModified (03 Response handling).
            assertThat((outcome as FetchOutcome.Http).code).isEqualTo(304)
        }

    @Test
    fun `401 basic challenge sets basicChallenge and realm`() =
        runBlocking {
            server.enqueue(
                mockResponse(
                    401,
                    "",
                    "WWW-Authenticate" to "Basic realm=\"member feed\"",
                ),
            )
            val outcome =
                fetcher().use { bundle ->
                    bundle.fetcher.fetch(request(server.url("/feed.xml").toString()))
                }
            outcome as FetchOutcome.Http
            assertThat(outcome.basicChallenge).isTrue()
            assertThat(outcome.realm).isEqualTo("member feed")
        }

    @Test
    fun `401 bearer challenge is not a basic challenge`() =
        runBlocking {
            server.enqueue(
                mockResponse(401, "", "WWW-Authenticate" to "Bearer realm=\"api\""),
            )
            val outcome =
                fetcher().use { bundle ->
                    bundle.fetcher.fetch(request(server.url("/feed.xml").toString()))
                }
            outcome as FetchOutcome.Http
            assertThat(outcome.basicChallenge).isFalse()
        }

    @Test
    fun `a basic challenge is found among several challenges`() =
        runBlocking {
            server.enqueue(
                mockResponse(
                    401,
                    "",
                    "WWW-Authenticate" to "Digest realm=\"sync\", nonce=\"n\", qop=\"auth\"",
                    "WWW-Authenticate" to "Basic realm=\"members\"",
                ),
            )
            val outcome =
                fetcher().use { bundle ->
                    bundle.fetcher.fetch(request(server.url("/feed.xml").toString()))
                }
            outcome as FetchOutcome.Http
            assertThat(outcome.basicChallenge).isTrue()
            assertThat(outcome.realm).isEqualTo("members")
        }

    @Test
    fun `a packed challenge list finds basic after digest`() =
        runBlocking {
            server.enqueue(
                mockResponse(
                    401,
                    "",
                    "WWW-Authenticate" to "Digest realm=\"d\", Basic realm=\"packed\"",
                ),
            )
            val outcome =
                fetcher().use { bundle ->
                    bundle.fetcher.fetch(request(server.url("/feed.xml").toString()))
                }
            outcome as FetchOutcome.Http
            assertThat(outcome.basicChallenge).isTrue()
            assertThat(outcome.realm).isEqualTo("packed")
        }

    @Test
    fun `429 captures Retry-After seconds and HTTP-date`() =
        runBlocking {
            server.enqueue(mockResponse(429, "", "Retry-After" to "120"))
            server.enqueue(
                mockResponse(
                    429,
                    "",
                    // 2026-10-04T00:02:00Z = TestClock.DEFAULT_NOW + 2 min.
                    "Retry-After" to "Sun, 04 Oct 2026 00:02:00 GMT",
                ),
            )
            fetcher().use { bundle ->
                val seconds = bundle.fetcher.fetch(request(server.url("/a").toString()))
                val date = bundle.fetcher.fetch(request(server.url("/b").toString()))
                assertThat((seconds as FetchOutcome.Http).retryAfterMs).isEqualTo(120_000L)
                assertThat((date as FetchOutcome.Http).retryAfterMs).isEqualTo(120_000L)
            }
        }

    @Test
    fun `3xx without Location is the final response`() =
        runBlocking {
            server.enqueue(mockResponse(code = 302, body = ""))
            val outcome =
                fetcher().use { bundle ->
                    bundle.fetcher.fetch(request(server.url("/feed.xml").toString()))
                }
            outcome as FetchOutcome.Http
            assertThat(outcome.code).isEqualTo(302)
        }

    // --- Redirect chain -----------------------------------------------------------------------------

    @Test
    fun `redirects record every hop and the leading permanent run`() =
        runBlocking {
            server.enqueue(mockResponse(301, "", "Location" to "/b"))
            server.enqueue(mockResponse(301, "", "Location" to "/c"))
            server.enqueue(mockResponse(body = rssBody()))
            val base = server.url("/a").toString()
            val outcome =
                fetcher().use { bundle ->
                    bundle.fetcher.fetch(request(base))
                }
            outcome as FetchOutcome.Body
            assertThat(outcome.hops.map { it.status }).containsExactly(301, 301).inOrder()
            assertThat(outcome.hops.map { it.url })
                .containsExactly(base, server.url("/b").toString())
                .inOrder()
            assertThat(outcome.permanentUrl).isEqualTo(server.url("/c").toString())
            assertThat(outcome.finalUrl).isEqualTo(server.url("/c").toString())
            assertThat(outcome.requestedUrl).isEqualTo(base)
            FileSystem.SYSTEM.delete(outcome.file)
        }

    @Test
    fun `permanentUrl stops at the first non-permanent hop`() =
        runBlocking {
            server.enqueue(mockResponse(301, "", "Location" to "/b"))
            server.enqueue(mockResponse(302, "", "Location" to "/c"))
            server.enqueue(mockResponse(301, "", "Location" to "/d"))
            server.enqueue(mockResponse(body = rssBody()))
            val outcome =
                fetcher().use { bundle ->
                    bundle.fetcher.fetch(request(server.url("/a").toString()))
                }
            outcome as FetchOutcome.Body
            // Only the leading run of 301/308 hops updates the permanent target (03 Request rules).
            assertThat(outcome.permanentUrl).isEqualTo(server.url("/b").toString())
            FileSystem.SYSTEM.delete(outcome.file)
        }

    @Test
    fun `temporary-only chain leaves permanentUrl null`() =
        runBlocking {
            server.enqueue(mockResponse(302, "", "Location" to "/b"))
            server.enqueue(mockResponse(301, "", "Location" to "/c"))
            server.enqueue(mockResponse(body = rssBody()))
            val outcome =
                fetcher().use { bundle ->
                    bundle.fetcher.fetch(request(server.url("/a").toString()))
                }
            outcome as FetchOutcome.Body
            // A 302 first means the leading permanent run is empty — later 301s do not count.
            assertThat(outcome.permanentUrl).isNull()
            FileSystem.SYSTEM.delete(outcome.file)
        }

    @Test
    fun `relative Location resolves against the hop URL`() =
        runBlocking {
            server.enqueue(mockResponse(308, "", "Location" to "../feeds/main.xml"))
            server.enqueue(mockResponse(body = rssBody()))
            val outcome =
                fetcher().use { bundle ->
                    bundle.fetcher.fetch(request(server.url("/old/feed.xml").toString()))
                }
            outcome as FetchOutcome.Body
            assertThat(outcome.finalUrl).isEqualTo(server.url("/feeds/main.xml").toString())
            FileSystem.SYSTEM.delete(outcome.file)
        }

    @Test
    fun `query-only and network-path Locations resolve against the hop`() =
        runBlocking {
            server.enqueue(mockResponse(302, "", "Location" to "?page=2"))
            server.enqueue(
                mockResponse(
                    302,
                    "",
                    "Location" to "//${server.hostName}:${server.port}/other",
                ),
            )
            server.enqueue(mockResponse(body = rssBody()))
            val outcome =
                fetcher().use { bundle ->
                    bundle.fetcher.fetch(request(server.url("/feed.xml").toString()))
                }
            outcome as FetchOutcome.Body
            // `?page=2` replaced the query in place, `//host/other` kept the scheme (RFC 3986).
            assertThat(outcome.finalUrl).isEqualTo(server.url("/other").toString())
            FileSystem.SYSTEM.delete(outcome.file)
        }

    @Test
    fun `a fragment-only Location is the final response`() =
        runBlocking {
            server.enqueue(mockResponse(302, "", "Location" to "/feed.xml#section"))
            val outcome =
                fetcher().use { bundle ->
                    bundle.fetcher.fetch(request(server.url("/feed.xml").toString()))
                }
            // Resolves back to the request URL with the fragment dropped — unusable (03).
            outcome as FetchOutcome.Http
            assertThat(outcome.code).isEqualTo(302)
        }

    @Test
    fun `a repeated URL is a redirect loop`() =
        runBlocking {
            server.enqueue(mockResponse(302, "", "Location" to "/b"))
            server.enqueue(mockResponse(302, "", "Location" to "/a"))
            val outcome =
                fetcher().use { bundle ->
                    bundle.fetcher.fetch(request(server.url("/a").toString()))
                }
            assertThat(outcome).isEqualTo(FetchOutcome.RedirectLoop)
        }

    @Test
    fun `more than twenty follow-ups is a redirect loop`() =
        runBlocking {
            // Each hop's Location points at the next counter; 21 follow-ups trip the cap.
            repeat(MAX_REDIRECT_FOLLOWUPS + 1) { hop ->
                server.enqueue(
                    mockResponse(302, "", "Location" to "/hop${hop + 1}"),
                )
            }
            server.enqueue(mockResponse(body = rssBody()))
            val outcome =
                fetcher().use { bundle ->
                    bundle.fetcher.fetch(request(server.url("/hop0").toString()))
                }
            assertThat(outcome).isEqualTo(FetchOutcome.RedirectLoop)
        }

    @Test
    fun `non-http Location is the final response`() =
        runBlocking {
            server.enqueue(mockResponse(302, "", "Location" to "ftp://example.com/f"))
            val outcome =
                fetcher().use { bundle ->
                    bundle.fetcher.fetch(request(server.url("/feed.xml").toString()))
                }
            outcome as FetchOutcome.Http
            assertThat(outcome.code).isEqualTo(302)
        }

    // --- Body policy --------------------------------------------------------------------------------

    @Test
    fun `gzip bodies decompress transparently and hash decoded bytes`() =
        runBlocking {
            val text = rssBody(items = arrayOf(rssItem("gz")))
            val gzipped =
                ByteArrayOutputStream().use { out ->
                    GZIPOutputStream(out).use { it.write(text.toByteArray()) }
                    out.toByteArray()
                }
            server.enqueue(
                mockResponse(code = 200, body = "").let {
                    it
                        .newBuilder()
                        .headers(headersOf("Content-Encoding", "gzip"))
                        .body(Buffer().write(gzipped))
                        .build()
                },
            )
            val outcome =
                fetcher().use { bundle ->
                    bundle.fetcher.fetch(request(server.url("/feed.xml").toString()))
                }
            outcome as FetchOutcome.Body
            assertThat(server.takeRequest().headers["Accept-Encoding"]).isEqualTo("gzip")
            assertThat(outcome.sha256Hex).isEqualTo(text.encodeUtf8().sha256().hex())
            assertThat(outcome.sniff).isEqualTo(Sniff.RSS)
            FileSystem.SYSTEM.delete(outcome.file)
        }

    @Test
    fun `body over maxBytes is TooLarge and the temp file is gone`() =
        runBlocking {
            val big = "<rss version=\"2.0\"><channel>" + "x".repeat(8 * 1024) + "</channel></rss>"
            server.enqueue(mockResponse(body = big))
            fetcher().use { bundle ->
                val outcome =
                    bundle.fetcher.fetch(
                        FeedRequest(
                            url = server.url("/feed.xml").toString(),
                            etag = null,
                            lastModified = null,
                            conditional = false,
                            maxBytes = 1024,
                        ),
                    )
                assertThat(outcome).isEqualTo(FetchOutcome.TooLarge)
                val feedsDir = File(root, "cache/feeds")
                assertThat(feedsDir.list()?.toList() ?: emptyList<String>()).isEmpty()
            }
        }

    @Test
    fun `utf-16 and utf-32 bodies sniff as rss`() =
        runBlocking {
            val doc =
                "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>E</title></channel></rss>"
            server.enqueue(rawBody(utf16Le(doc)))
            server.enqueue(rawBody(utf16Be(doc)))
            server.enqueue(rawBody(utf32Le(doc)))
            server.enqueue(rawBody(utf32Be(doc)))
            fetcher().use { bundle ->
                for (path in listOf("/le16", "/be16", "/le32", "/be32")) {
                    val outcome = bundle.fetcher.fetch(request(server.url(path).toString()))
                    assertThat((outcome as FetchOutcome.Body).sniff).named(path).isEqualTo(Sniff.RSS)
                    FileSystem.SYSTEM.delete(outcome.file)
                }
            }
        }

    @Test
    fun `a long comment prolog still sniffs inside the window`() =
        runBlocking {
            // The first element sits ~40 KB in — past any small probe, inside 64 KiB (03).
            val doc = "<!-- " + "x".repeat(40_000) + " -->\n" + rssBody()
            server.enqueue(mockResponse(body = doc))
            val outcome =
                fetcher().use { bundle ->
                    bundle.fetcher.fetch(request(server.url("/feed.xml").toString()))
                }
            outcome as FetchOutcome.Body
            assertThat(outcome.sniff).isEqualTo(Sniff.RSS)
            FileSystem.SYSTEM.delete(outcome.file)
        }

    @Test
    fun `a body-write failure is a Storage outcome and no temp file remains`() =
        runBlocking {
            server.enqueue(mockResponse(body = rssBody()))
            val broken =
                object : ForwardingFileSystem(FileSystem.SYSTEM) {
                    override fun sink(
                        file: Path,
                        mustCreate: Boolean,
                    ): Sink = throw IOException("simulated disk full")
                }
            setUpRoot()
            val clients = OkHttpNeutrodyneHttpClients(newNetworkClients(), testUserAgent())
            try {
                val tempFiles = FeedTempFiles(storagePathsFor(root), FileSystem.SYSTEM)
                val fetcher =
                    FeedFetcher(clients, tempFiles, broken, testClassifier, CredentialLookup.None, clock)
                val outcome = fetcher.fetch(request(server.url("/feed.xml").toString()))
                // Sink/disk faults classify as STORAGE, not transport failures (03 Response).
                assertThat(outcome).isInstanceOf(FetchOutcome.Storage::class.java)
                val feedsDir = File(root, "cache/feeds")
                assertThat(feedsDir.list()?.toList() ?: emptyList<String>()).isEmpty()
            } finally {
                clients.close()
            }
        }

    @Test
    fun `sniffOnlyBytes stops the body read early`() =
        runBlocking {
            val body = rssBody() + "x".repeat(64 * 1024)
            server.enqueue(mockResponse(body = body))
            val outcome =
                fetcher().use { bundle ->
                    bundle.fetcher.fetch(
                        FeedRequest(
                            url = server.url("/feed.xml").toString(),
                            etag = null,
                            lastModified = null,
                            conditional = false,
                            sniffOnlyBytes = 1024,
                        ),
                    )
                }
            outcome as FetchOutcome.Body
            // The read stops once `sniffOnlyBytes` is buffered — overshoot is one read chunk.
            val size = FileSystem.SYSTEM.metadata(outcome.file).size ?: 0
            assertThat(size).isAtLeast(1024L)
            assertThat(size).isLessThan(body.length.toLong())
            FileSystem.SYSTEM.delete(outcome.file)
        }

    // --- Credentials --------------------------------------------------------------------------------

    @Test
    fun `request credentials send Basic on the same origin`() =
        runBlocking {
            server.enqueue(mockResponse(body = rssBody()))
            fetcher().use { bundle ->
                bundle.fetcher.fetch(
                    FeedRequest(
                        url = server.url("/feed.xml").toString(),
                        etag = null,
                        lastModified = null,
                        conditional = false,
                        credentials = BasicCredentials("user", "pass"),
                    ),
                )
            }
            val recorded = server.takeRequest()
            val expected = "Basic " + Base64.getEncoder().encodeToString("user:pass".toByteArray())
            assertThat(recorded.headers["Authorization"]).isEqualTo(expected)
        }

    @Test
    fun `request credentials never ride a cross-origin hop`() =
        runBlocking {
            val second = MockWebServer()
            try {
                second.start()
                server.enqueue(
                    mockResponse(302, "", "Location" to second.url("/f").toString()),
                )
                second.enqueue(mockResponse(body = rssBody()))
                fetcher().use { bundle ->
                    bundle.fetcher.fetch(
                        FeedRequest(
                            url = server.url("/feed.xml").toString(),
                            etag = null,
                            lastModified = null,
                            conditional = false,
                            credentials = BasicCredentials("user", "pass"),
                        ),
                    )
                }
                assertThat(server.takeRequest().headers["Authorization"]).isNotNull()
                assertThat(second.takeRequest().headers["Authorization"]).isNull()
            } finally {
                second.close()
            }
        }

    @Test
    fun `stored credentials attach on same-origin hops only`() =
        runBlocking {
            val second = MockWebServer()
            try {
                second.start()
                val lookup =
                    CredentialLookup { origin ->
                        if (origin.scheme == "http" && origin.port == server.port) {
                            "Basic c3RvcmVk" // stored
                        } else {
                            null
                        }
                    }
                server.enqueue(
                    mockResponse(302, "", "Location" to second.url("/f").toString()),
                )
                second.enqueue(mockResponse(body = rssBody()))
                fetcher(lookup).use { bundle ->
                    bundle.fetcher.fetch(request(server.url("/feed.xml").toString()))
                }
                assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Basic c3RvcmVk")
                assertThat(second.takeRequest().headers["Authorization"]).isNull()
            } finally {
                second.close()
            }
        }

    private fun rawBody(bytes: ByteArray): MockResponse =
        MockResponse.Builder().code(200).body(Buffer().write(bytes)).build()

    private fun utf16Le(s: String): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)

    private fun utf16Be(s: String): ByteArray =
        byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + s.toByteArray(Charsets.UTF_16BE)

    private fun utf32Le(s: String): ByteArray {
        // BOM FF FE 00 00, then one little-endian 32-bit unit per (ASCII) char.
        val out = ByteArray(4 + s.length * 4)
        out[0] = 0xFF.toByte()
        out[1] = 0xFE.toByte()
        for ((i, c) in s.withIndex()) out[4 + i * 4] = c.code.toByte()
        return out
    }

    private fun utf32Be(s: String): ByteArray {
        val out = ByteArray(4 + s.length * 4)
        out[2] = 0xFE.toByte()
        out[3] = 0xFF.toByte()
        for ((i, c) in s.withIndex()) out[4 + i * 4 + 3] = c.code.toByte()
        return out
    }

    private companion object {
        const val FEED_ACCEPT_VALUE =
            "application/rss+xml, application/atom+xml;q=0.9, application/xml;q=0.8, text/xml;q=0.8, */*;q=0.5"
    }
}
