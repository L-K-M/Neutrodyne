// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.add.AddInputNormalizer
import ch.lkmc.neutrodyne.core.data.add.NormalizedInput
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.AddResolution
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedPreview
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import kotlinx.coroutines.test.runTest
import mockwebserver3.junit4.MockWebServerRule
import org.junit.Rule
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 03 Add podcast flow — the M1a slice of `AddPodcastResolverImpl` over the real fetch/parse stack:
 * input normalisation, host checks, sniffing outcomes, auth, the preview cache and dedupe. HTML
 * autodiscovery, directory lookups and `Choose` are M7; `setCredentials` re-probing is M1b.
 */
class AddPodcastResolverTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    private val clock = TestClock()
    private val db = newDb(clock)

    private lateinit var root: File
    private lateinit var bundle: ResolverBundle

    @BeforeTest
    fun setUp() {
        root =
            kotlin.io.path
                .createTempDirectory("nd-add-test")
                .toFile()
        bundle = newResolver(root, db, clock)
    }

    @AfterTest
    fun tearDown() {
        bundle.close()
        root.deleteRecursively()
    }

    private fun feedUrl(path: String = "/feed.xml") = server.url(path).toString()

    // --- Input normalisation (03 steps 1–7) ---------------------------------------------------------

    @Test
    fun schemeWrappersUnwrapToTheFetchUrl() {
        assertUrl("feed:https://a.example.com/f", "https://a.example.com/f")
        assertUrl("pcast://a.example.com/f", "https://a.example.com/f", guessed = true)
        assertUrl("podcast://a.example.com/f", "https://a.example.com/f", guessed = true)
        assertUrl("itpc://a.example.com/f", "https://a.example.com/f", guessed = true)
    }

    @Test
    fun bareHostAndUppercaseSchemeNormalise() {
        assertUrl("a.example.com/feed.xml", "https://a.example.com/feed.xml", guessed = true)
        assertUrl("HTTPS://Host.example.com/Feed", "https://Host.example.com/Feed")
    }

    @Test
    fun feedSchemeWithSlashesIsSchemeGuessed() {
        // `feed://host/path` unwraps like `feed:http(s)://…` (03 step 3): bare host → https guess.
        assertUrl("feed://a.example.com/f", "https://a.example.com/f", guessed = true)
        assertUrl("feed:http://a.example.com/f", "http://a.example.com/f")
    }

    @Test
    fun shareIntentTextYieldsTheFirstUrlToken() {
        val input = "  Listen to this https://a.example.com/f right now "
        assertUrl(input, "https://a.example.com/f")
    }

    @Test
    fun userinfoBecomesCredentialsAndLeavesTheUrl() {
        val n = AddInputNormalizer.normalize("https://user:p%40ss@a.example.com/f")
        val url = assertIs<NormalizedInput.Url>(n)
        assertEquals("https://a.example.com/f", url.url)
        assertEquals("user", url.credentials?.username)
        assertEquals("p@ss", url.credentials?.password)
    }

    @Test
    fun nonHttpSchemesAreInvalidAndPlainTextIsNotAUrl() {
        assertIs<NormalizedInput.Invalid>(AddInputNormalizer.normalize("ftp://a.example.com/f"))
        val n = AddInputNormalizer.normalize("some podcast title")
        val notAUrl = assertIs<NormalizedInput.NotAUrl>(n)
        assertEquals("some podcast title", notAUrl.query)
    }

    @Test
    fun subscribePageWrappersUnwrap() {
        assertUrl(
            "neutrodyne://subscribe?url=https%3A%2F%2Fa.example.com%2Ff",
            "https://a.example.com/f",
        )
        assertUrl(
            "https://antennapod.org/deeplink/subscribe?url=https%3A%2F%2Fa.example.com%2Ff&x=1",
            "https://a.example.com/f",
        )
        assertUrl(
            "https://subscribeonandroid.com/a.example.com/feed.xml",
            "https://a.example.com/feed.xml",
            guessed = true,
        )
        val enc =
            Base64.UrlSafe.encode("https://a.example.com/f".encodeToByteArray()).trimEnd('=')
        assertUrl(
            "https://podcasts.google.com/feed/$enc",
            "https://a.example.com/f",
        )
    }

    private fun assertUrl(
        input: String,
        expected: String,
        guessed: Boolean = false,
    ) {
        val n = assertIs<NormalizedInput.Url>(AddInputNormalizer.normalize(input))
        assertEquals(expected, n.url)
        assertEquals(guessed, n.schemeGuessed)
    }

    // --- Host checks (03 Host recognition; YouTube is M8) -------------------------------------------

    @Test
    fun youTubeInputFailsWithoutFetching() =
        runTest {
            for (input in listOf(
                "https://www.youtube.com/@someone",
                "youtu.be/abc",
                "check https://youtube.com/c/x out",
            )) {
                val r = bundle.resolver.resolve(input)
                val failure = assertIs<AddResolution.Failure>(r)
                assertEquals(AddPodcastError.YouTubeNotYetSupported, failure.error)
            }
            assertEquals(0, server.requestCount)
        }

    @Test
    fun spotifyShowIsExplained() =
        runTest {
            val r = bundle.resolver.resolve("https://open.spotify.com/show/abc")
            val failure = assertIs<AddResolution.Failure>(r)
            assertEquals(AddPodcastError.SpotifyShow, failure.error)
        }

    // --- Fetch, sniff and preview (03 Fetch/sniff, Preview and dedupe) ------------------------------

    @Test
    fun rssDocumentResolvesToAFeedPreview() =
        runTest {
            server.enqueue(
                mockResponse(
                    body =
                        rssBody(
                            title = "The Show",
                            items = arrayOf(rssItem("e1", title = "First")),
                        ),
                    headers = arrayOf("ETag" to "\"e1\""),
                ),
            )

            val r = bundle.resolver.resolve(feedUrl())

            val feed = assertIs<AddResolution.Feed>(r)
            val preview = feed.preview
            assertEquals("The Show", preview.title)
            assertEquals(1, preview.episodeCount)
            assertEquals("First", preview.episodes.single().title)
            assertNull(preview.alreadySubscribed)
            assertFalse(preview.emptyFeed)
            assertEquals(feedUrl(), preview.feedUrl)
            // The preview is cached under the resolved URL (03 Preview and dedupe).
            assertTrue(bundle.cache.get(preview.previewId) != null)
        }

    @Test
    fun sniffedNonFeedsFailWithTheRightError() =
        runTest {
            server.enqueue(mockResponse(body = "<!DOCTYPE html><html><body>hi</body></html>"))
            assertEquals(
                AddPodcastError.NotAFeed,
                assertIs<AddResolution.Failure>(bundle.resolver.resolve(feedUrl())).error,
            )

            server.enqueue(mockResponse(body = """{"a":1}"""))
            assertEquals(
                AddPodcastError.NotAFeed,
                assertIs<AddResolution.Failure>(bundle.resolver.resolve(feedUrl())).error,
            )

            val opmlBody =
                """<?xml version="1.0"?><opml version="1.0"><head/><body><outline text="x"/></body></opml>"""
            server.enqueue(mockResponse(body = opmlBody))
            val opml = assertIs<AddResolution.Failure>(bundle.resolver.resolve(feedUrl()))
            assertEquals(AddPodcastError.SubscriptionList(feedUrl()), opml.error)
        }

    @Test
    fun httpAndParseFailuresMapToErrors() =
        runTest {
            server.enqueue(mockResponse(code = 404, body = "nope"))
            assertEquals(
                AddPodcastError.Http(404),
                assertIs<AddResolution.Failure>(bundle.resolver.resolve(feedUrl())).error,
            )

            server.enqueue(mockResponse(body = rssBody(items = arrayOf("<item><title>no media</title></item>"))))
            assertEquals(
                AddPodcastError.NoMedia,
                assertIs<AddResolution.Failure>(bundle.resolver.resolve(feedUrl())).error,
            )

            // A start tag over the 1,000-attribute bound trips `TagBounds` → MALFORMED (feeds
            // limits); kxml2's relaxed mode would otherwise tolerate the document.
            val fat = "<rss version=\"2.0\" " + (0..1_000).joinToString(" ") { "a$it=\"x\"" } + "><channel/></rss>"
            server.enqueue(mockResponse(body = fat))
            assertEquals(
                AddPodcastError.Malformed,
                assertIs<AddResolution.Failure>(bundle.resolver.resolve(feedUrl())).error,
            )
        }

    @Test
    fun emptyFeedStillPreviews() =
        runTest {
            server.enqueue(mockResponse(body = rssBody(title = "Fresh Show")))

            val feed = assertIs<AddResolution.Feed>(bundle.resolver.resolve(feedUrl()))
            assertTrue(feed.preview.emptyFeed)
            assertEquals(0, feed.preview.episodeCount)
        }

    @Test
    fun anEmptyListFeedIsRejectedBeforePreview() =
        runTest {
            // `podcast:medium` ending in `L` is a list of OTHER feeds — nothing to subscribe to
            // (03 Accepted items): rejected at resolve, never previewed (S14).
            server.enqueue(
                mockResponse(
                    body =
                        """<?xml version="1.0"?>
<rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0">
  <channel><title>A List</title>
    <podcast:medium>podcastL</podcast:medium>
    <podcast:remoteItem medium="podcast" feedGuid="917393e3-1b1e-5cef-ace4-edaa54e1f810"/>
  </channel></rss>""",
                ),
            )

            val r = bundle.resolver.resolve(feedUrl())

            assertEquals(
                AddPodcastError.UnsupportedListFeed,
                assertIs<AddResolution.Failure>(r).error,
            )
        }

    @Test
    fun basicChallengeAsksForCredentials() =
        runTest {
            server.enqueue(
                mockResponse(
                    401,
                    "",
                    "WWW-Authenticate" to """Basic realm="members"""",
                ),
            )

            val r = bundle.resolver.resolve(feedUrl())
            assertEquals(
                AddPodcastError.AuthRequired("members"),
                assertIs<AddResolution.Failure>(r).error,
            )
        }

    @Test
    fun typedCredentialsAreSentOnTheRetry() =
        runTest {
            server.enqueue(mockResponse(body = rssBody()))

            val r =
                bundle.resolver.resolve(feedUrl(), BasicCredentials("u", "p"))

            assertIs<AddResolution.Feed>(r)
            assertEquals("Basic dTpw", server.takeRequest().headers["Authorization"])
        }

    @Test
    fun userinfoCredentialsAreSentAndStripped() =
        runTest {
            server.enqueue(mockResponse(body = rssBody()))

            val r = bundle.resolver.resolve("http://u2:p2@${server.hostName}:${server.port}/feed.xml")

            val feed = assertIs<AddResolution.Feed>(r)
            assertEquals("Basic dTI6cDI=", server.takeRequest().headers["Authorization"])
            assertFalse(feed.preview.feedUrl.contains("@"))
        }

    @Test
    fun schemeGuessedInputRetriesOverHttp() =
        runTest {
            // A bare `host:port/path` is guessed as https; the port is plaintext http, so the
            // transport failure retries once over `http://` (03 step 5) and succeeds.
            server.enqueue(mockResponse(body = rssBody(title = "Retry Show")))

            val r = bundle.resolver.resolve("127.0.0.1:${server.port}/feed.xml")

            val feed = assertIs<AddResolution.Feed>(r)
            assertEquals("Retry Show", feed.preview.title)
            // The resolved URL is `http://`, which can only come from the scheme-guess retry
            // (an https attempt answered over cleartext fallback would keep `https://`).
            assertEquals("http://127.0.0.1:${server.port}/feed.xml", feed.preview.feedUrl)
        }

    @Test
    fun previewReusesALiveCacheEntry() =
        runTest {
            server.enqueue(mockResponse(body = rssBody(title = "Cached")))
            assertIs<AddResolution.Feed>(bundle.resolver.resolve(feedUrl()))
            assertEquals(1, server.requestCount)

            val cached = assertIs<Outcome.Success<FeedPreview>>(bundle.resolver.preview(feedUrl()))
            assertEquals("Cached", cached.value.title)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun previewFetchesAnUnknownUrl() =
        runTest {
            server.enqueue(mockResponse(body = rssBody(title = "Fresh")))

            val r = bundle.resolver.preview(feedUrl())

            val preview = assertIs<Outcome.Success<FeedPreview>>(r).value
            assertEquals("Fresh", preview.title)
        }

    @Test
    fun dedupeMarksAnAlreadySubscribedFeed() =
        runTest {
            val existing =
                seedPodcast(
                    db,
                    feedUrl = feedUrl(),
                    feedKey = UrlNormalizer.forIdentity(feedUrl()) ?: error("bad url"),
                )
            server.enqueue(mockResponse(body = rssBody()))

            val feed = assertIs<AddResolution.Feed>(bundle.resolver.resolve(feedUrl()))

            val already = feed.preview.alreadySubscribed
            assertEquals(existing, already?.podcastId)
            assertEquals(true, already?.exact)
        }

    @Test
    fun networkFailureSurfacesTheClassifiedError() =
        runTest {
            // Nothing listens here; the transport error classifies (not DNS of a live host).
            val r = bundle.resolver.resolve("http://127.0.0.1:1/feed.xml")

            val failure = assertIs<AddResolution.Failure>(r)
            val error = assertIs<AddPodcastError.Network>(failure.error)
            assertEquals(NetError.ConnectionFailed, error.error)
        }

    // --- Redirect handling of previews (03 Preview and dedupe) --------------------------------------

    @Test
    fun permanentRedirectResolvesTheFinalUrlAsPreviewId() =
        runTest {
            server.enqueue(mockResponse(301, "", "Location" to "/new.xml"))
            server.enqueue(mockResponse(body = rssBody(title = "Moved")))

            val feed = assertIs<AddResolution.Feed>(bundle.resolver.resolve(feedUrl("/old.xml")))

            assertEquals(feedUrl("/new.xml"), feed.preview.feedUrl)
            assertEquals(feedUrl("/new.xml"), feed.preview.previewId)
            // The cache reuse lookup hits on the typed URL too (`inputUrl` of the entry).
            assertTrue(bundle.cache.get(feedUrl("/new.xml")) != null)
        }

    @Test
    fun aTemporaryRedirectKeepsTheRequestedUrlAsPreviewId() =
        runTest {
            server.enqueue(mockResponse(302, "", "Location" to "/temp.xml"))
            server.enqueue(mockResponse(body = rssBody(title = "CDN copy")))

            val feed = assertIs<AddResolution.Feed>(bundle.resolver.resolve(feedUrl("/old.xml")))

            // `permanentUrl ?: requestedUrl` (03 redirects): a 302's target must not become the
            // subscription identity — the preview stays keyed on the requested URL (S6).
            assertEquals(feedUrl("/old.xml"), feed.preview.feedUrl)
            assertEquals(feedUrl("/old.xml"), feed.preview.previewId)
        }

    @Test
    fun aPrivateRequestedUrlKeepsTheChipOverAPublicTerminal() =
        runTest {
            // The token lives on the SUBSCRIPTION URL (a 302 keeps it as identity); a public-looking
            // CDN target must not hide the "Private feed" chip (03 Preview and dedupe).
            server.enqueue(mockResponse(302, "", "Location" to "/cdn/plain.xml"))
            server.enqueue(mockResponse(body = rssBody(title = "Members")))

            val feed =
                assertIs<AddResolution.Feed>(
                    bundle.resolver.resolve(feedUrl("/feed.xml") + "?token=abc"),
                )

            assertTrue(feed.preview.isPrivate)
        }

    @Test
    fun theNormalisedInputFeedsDedupe() =
        runTest {
            // The podcast's feedKey is the HTTPS form; `pcast://` input normalises to it (S4).
            // Without the normalised `inputUrl` the scheme-guess retry leaves only `http://`
            // candidates and the dedupe misses.
            val existing =
                seedPodcast(
                    db,
                    feedUrl = "https://127.0.0.1:${server.port}/feed.xml",
                    feedKey =
                        UrlNormalizer.forIdentity("https://127.0.0.1:${server.port}/feed.xml")
                            ?: error("bad url"),
                )
            server.enqueue(mockResponse(body = rssBody()))

            val feed =
                assertIs<AddResolution.Feed>(
                    bundle.resolver.resolve("pcast://127.0.0.1:${server.port}/feed.xml"),
                )

            assertEquals(existing, feed.preview.alreadySubscribed?.podcastId)
            assertEquals(true, feed.preview.alreadySubscribed?.exact)
        }
}
