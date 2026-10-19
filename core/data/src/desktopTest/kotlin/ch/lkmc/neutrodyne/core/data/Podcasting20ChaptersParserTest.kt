// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.data.playback.Podcasting20ChaptersParser
import ch.lkmc.neutrodyne.core.network.NeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Podcasting20ChaptersParserTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val clients = OkHttpNeutrodyneHttpClients(newNetworkClients(), testUserAgent())
    private val client = clients.client(HttpClientKind.API)
    private val parser =
        Podcasting20ChaptersParser(
            object : NeutrodyneHttpClients {
                override fun client(kind: HttpClientKind): HttpClient {
                    assertEquals(HttpClientKind.API, kind)
                    return client
                }
            },
        )

    @After fun close() = clients.close()

    @Test fun completeUnsortedDuplicateAndRelativeUrls() {
        val result =
            parse(
                """{"startTime":2,"title":"second","img":"../i.png","url":"/about"},{"startTime":0},{"startTime":2,"title":"ignored"}""",
            )
        assertEquals(listOf(0L, 2000L), result.map { it.startMs })
        assertEquals(2000, result[0].endMs)
        assertEquals("second", result[1].title)
        assertEquals("https://example.test/i.png", result[1].imageUrl)
        assertEquals("https://example.test/about", result[1].linkUrl)
    }

    @Test fun fragmentLinksAreRetainedAndStringTocIsIgnored() {
        val row = parse("""{"startTime":0,"url":"#section","toc":"false"}""").single()
        assertEquals("https://example.test/chapters/list.json#section", row.linkUrl)
        assertTrue(!row.hidden)
    }

    @Test fun generatedJsonFixtureParses() {
        val root = checkNotNull(System.getProperty("neutrodyne.rootDir"))
        val file = java.io.File(root, "playback/impl/src/test/resources/media/chapters.json")
        val rows = Podcasting20ChaptersParser.parse(file.readText(), "https://example.test/c")
        assertEquals(listOf("JSON opening", "JSON closing"), rows.map { it.title })
    }

    @Test fun badDocumentShapeIsRejected() {
        for (text in listOf("[]", "{}", """{"version":1,"chapters":[]}""", """{"version":"1","chapters":{}}""", "{")) {
            assertFailsWith<Exception> { Podcasting20ChaptersParser.parse(text, "https://example.test/c") }
        }
    }

    @Test fun invalidTimesDroppedAndMalformedEndsDefault() {
        val result =
            parse(
                """{"startTime":-1},{"startTime":"2"},{"startTime":1e300},{"startTime":1e999},{"startTime":null},{"title":"missing"},{"startTime":0,"endTime":-1},{"startTime":1.25,"endTime":"4"}""",
            )
        assertEquals(listOf(0L, 1250L), result.map { it.startMs })
        assertEquals(listOf(1250L, null), result.map { it.endMs })
    }

    @Test fun hiddenOnlyAndEmptyAreSuccessful() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("""{"startTime":0,"toc":false}""").single().hidden)
    }

    @Test fun unsafeUrlsAndWrongOptionalTypesDiscarded() {
        val row = parse("""{"startTime":0,"title":5,"img":"file:///x","url":"javascript:alert(1)"}""").single()
        assertNull(row.title)
        assertNull(row.imageUrl)
        assertNull(row.linkUrl)
    }

    @Test fun cappedAtOneThousandAfterSortingAndDeduplication() {
        val rows = parse((1005 downTo 0).joinToString(",") { """{"startTime":$it}""" })
        assertEquals(1000, rows.size)
        assertEquals(0, rows.first().startMs)
        assertEquals(999_000, rows.last().startMs)
    }

    @Test fun networkAcceptsParseableBodyRegardlessOfMime() =
        runBlocking<Unit> {
            serverRule.server.enqueue(
                MockResponse
                    .Builder()
                    .body(
                        """{"version":"1.2.0","chapters":[{"startTime":0}]}""",
                    ).addHeader("Content-Type", "text/plain")
                    .build(),
            )
            assertEquals(1, parser.fetch(serverRule.server.url("/c").toString()).size)
        }

    @Test fun streamingCapWithoutContentLength() =
        runBlocking<Unit> {
            serverRule.server.enqueue(MockResponse.Builder().chunkedBody(" ".repeat(1024 * 1024 + 1), 8192).build())
            val error =
                assertFailsWith<IllegalArgumentException> {
                    parser.fetch(serverRule.server.url("/c").toString())
                }
            assertEquals("Chapter document exceeds 1 MiB", error.message)
        }

    @Test fun slowResponseTimesOutAfterEightSeconds() =
        runBlocking<Unit> {
            val response =
                MockResponse
                    .Builder()
                    .body("{}")
                    .bodyDelay(10, TimeUnit.SECONDS)
                    .build()
            serverRule.server.enqueue(response)
            assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
                parser.fetch(serverRule.server.url("/slow").toString())
            }
        }

    @Test fun cancellationPropagates() =
        runBlocking<Unit> {
            serverRule.server.enqueue(
                MockResponse
                    .Builder()
                    .headersDelay(20, TimeUnit.SECONDS)
                    .body("{}")
                    .build(),
            )
            val work = async { parser.fetch(serverRule.server.url("/c").toString()) }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { serverRule.server.takeRequest() }
            work.cancel()
            assertFailsWith<CancellationException> { work.await() }
        }

    private fun parse(chapters: String) =
        Podcasting20ChaptersParser.parse(
            """{"version":"1.2.0","chapters":[$chapters]}""",
            "https://example.test/chapters/list.json",
        )
}
