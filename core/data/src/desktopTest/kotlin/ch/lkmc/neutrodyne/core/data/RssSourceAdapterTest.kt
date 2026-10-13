// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.refresh.FetchMode
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.test.runTest
import mockwebserver3.junit4.MockWebServerRule
import org.junit.Rule
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `RssSourceAdapter` seams the engine tests do not reach: the per-host semaphore key
 * (03's ≤2-per-host politeness limit) and the OLDER_PAGE cursor contract.
 */
class RssSourceAdapterTest {
    @get:Rule
    val serverRule = MockWebServerRule()

    private val server get() = serverRule.server

    private val clock = TestClock(NOW)
    private val db = newDb(clock)

    private lateinit var root: File

    @AfterTest
    fun tearDown() {
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun setUpRoot() {
        if (!::root.isInitialized) {
            root =
                kotlin.io.path
                    .createTempDirectory("nd-rss-adapter-test")
                    .toFile()
        }
    }

    @Test
    fun hostKeyStripsUserinfo() =
        runTest {
            setUpRoot()
            val feed = dueFeedOf(db, seedPodcast(db, "https://user:pass@feeds.example.com/show"))
            val bundle = newRssAdapter(root, clock)
            try {
                // Credentials are not part of the host — two auth'd URLs to one server must
                // share one politeness semaphore.
                assertEquals("feeds.example.com", bundle.adapter.hostKey(feed, feed.feedUrl))
            } finally {
                bundle.close()
            }
        }

    @Test
    fun hostKeyStripsQueryAndPort() =
        runTest {
            setUpRoot()
            val feed = dueFeedOf(db, seedPodcast(db, "https://feeds.example.com:8443?raw=1"))
            val bundle = newRssAdapter(root, clock)
            try {
                // 03 keys the limit on the host alone, as OkHttp's per-host limit does: feeds on
                // two ports of one server share one politeness semaphore.
                assertEquals("feeds.example.com", bundle.adapter.hostKey(feed, feed.feedUrl))
                assertEquals(
                    "[2001:db8::1]",
                    bundle.adapter.hostKey(feed, "https://[2001:DB8::1]:8080/f.xml"),
                )
            } finally {
                bundle.close()
            }
        }

    @Test
    fun hostKeyKeysOnTheRequestUrlNotTheFeedUrl() =
        runTest {
            setUpRoot()
            // An older-page link may point at another host — the semaphore must charge that host.
            val feed = dueFeedOf(db, seedPodcast(db, "https://a.example.com/f"))
            val bundle = newRssAdapter(root, clock)
            try {
                assertEquals(
                    "cdn.example.net",
                    bundle.adapter.hostKey(feed, "https://cdn.example.net/f?page=2"),
                )
            } finally {
                bundle.close()
            }
        }

    @Test
    fun hostKeyCanonicalisesNumericAndIdnHosts() =
        runTest {
            setUpRoot()
            val feed = dueFeedOf(db, seedPodcast(db, "https://a.example.com/f"))
            val bundle = newRssAdapter(root, clock)
            try {
                // OkHttp hands the verbatim host to InetAddress, which resolves "127.1" and
                // "2130706433" to 127.0.0.1; all three spellings must share the semaphore.
                assertEquals("127.0.0.1", bundle.adapter.hostKey(feed, "http://127.0.0.1/f"))
                assertEquals("127.0.0.1", bundle.adapter.hostKey(feed, "http://127.1/f"))
                assertEquals("127.0.0.1", bundle.adapter.hostKey(feed, "http://2130706433/f"))
                // An IDN and its punycode spelling are one host too.
                assertEquals("xn--mnchen-3ya.de", bundle.adapter.hostKey(feed, "https://MÜNCHEN.de/f"))
                assertEquals(
                    "xn--mnchen-3ya.de",
                    bundle.adapter.hostKey(feed, "https://xn--mnchen-3ya.de/f"),
                )
            } finally {
                bundle.close()
            }
        }

    @Test
    fun hostKeyFallsBackToRawAuthorityWhenUncanonicalisable() =
        runTest {
            setUpRoot()
            val feed = dueFeedOf(db, seedPodcast(db, "https://a.example.com/f"))
            val bundle = newRssAdapter(root, clock)
            try {
                // Port 0 is unfetchable, so the URL has no canonical identity; the semaphore
                // still needs a stable key, taken from the raw authority as before.
                assertEquals(
                    "feeds.example.com",
                    bundle.adapter.hostKey(feed, "https://FEEDS.EXAMPLE.COM:0/f"),
                )
            } finally {
                bundle.close()
            }
        }

    @Test
    fun olderPageWithoutACursorFailsFast() =
        runTest {
            // The engine only sends OLDER_PAGE when `pagingNextUrl` is set; a null cursor must
            // not silently re-fetch page 1.
            setUpRoot()
            val id = seedPodcast(db, server.url("/f").toString())
            server.enqueue(mockResponse(body = rssBody(items = arrayOf(rssItem("x")))))
            val bundle = newRssAdapter(root, clock)
            try {
                assertFailsWith<IllegalArgumentException> {
                    bundle.adapter.fetchAndParse(dueFeedOf(db, id), FetchMode.OLDER_PAGE)
                }
                assertEquals(0, server.requestCount)
            } finally {
                bundle.close()
            }
        }

    private companion object {
        const val NOW = 1_791_000_000_000L
    }
}
