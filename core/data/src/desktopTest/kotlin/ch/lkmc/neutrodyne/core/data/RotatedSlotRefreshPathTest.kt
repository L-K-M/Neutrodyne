// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.refresh.FeedOutcome
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshRequest
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.model.GuidKnowledge
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rotated-slot defect through the real consumer path — `FeedFetcher` →
 * `XmlPullFeedParser` → `RssSourceAdapter` → `FeedRefresher` → `FeedIngestor`, over a
 * MockWebServer and a file database that reopens mid-sequence. The enclosure host is a
 * rejected numeric form whose identity normalization returns null, so the parser-accepted
 * media URL leaves the item without usable enclosure evidence.
 */
internal class RotatedSlotRefreshPathTest {
    private fun rss(
        host: String,
        guids: List<Pair<String, String>>,
    ) = """<rss version="2.0"><channel><title>Review show</title>""" +
        guids.joinToString("") { (guid, title) ->
            """<item><guid>$guid</guid><title>$title</title><pubDate>Sun, 11 Oct 2026 12:00:00 GMT</pubDate><enclosure url="http://$host/${title.lowercase()}.mp3" type="audio/mpeg" length="12345"/></item>"""
        } +
        "</channel></rss>"

    private suspend fun preservesAcrossRealRefresh(host: String) {
        val clock = TestClock()
        val dir = Files.createTempDirectory("consumer-rotation-")
        val settings = FakeSettingsRepository()
        MockWebServer().use { server ->
            server.start()
            var db = TestDb.file(dir.resolve("db"), clock = clock)
            try {
                val id =
                    seedPodcast(
                        db,
                        feedUrl = server.url("/feed.xml").toString(),
                        guidCoverageSince = null,
                        initialFetch = true,
                    )

                suspend fun refresh(items: List<Pair<String, String>>) {
                    server.enqueue(mockResponse(body = rss(host, items)))
                    newRssAdapter(dir.resolve("rss-cache").toFile(), clock).use { adapter ->
                        val report =
                            newRefresher(
                                db,
                                mapOf(SourceType.RSS to adapter.adapter),
                                clock,
                                settings,
                                tempFiles = adapter.tempFiles,
                            ).run(
                                RefreshRequest(
                                    RefreshScope.Podcasts(listOf(id)),
                                    true,
                                    false,
                                    RefreshOrigin.MANUAL,
                                ),
                            )
                        assertIs<FeedOutcome.Ingested>(report.outcomes[id])
                        assertEquals(0, report.remaining)
                    }
                }

                refresh(listOf("x" to "Alpha", "y" to "Beta"))
                val a = db.ingestDao().existing(id).single { it.title == "Alpha" }
                val b = db.ingestDao().existing(id).single { it.title == "Beta" }
                db.episodeStateDao().upsert(episodeStateEntity(a.id, playedAt = clock.now() - 1000))
                db.episodeStateDao().upsert(episodeStateEntity(b.id).copy(isFavorite = true))

                refresh(listOf("y" to "Alpha", "y" to "Beta"))
                val shell = assertNotNull(db.episodeDao().byId(a.id))
                assertEquals("g:x", shell.identityKey)
                assertEquals("y", shell.guid)
                assertEquals(GuidKnowledge.KNOWN_AMBIGUOUS, db.ingestDao().guidKnowledge(id)["y"])
                assertNull(db.ingestDao().guidKnowledge(id)["x"])

                db.close()
                db = TestDb.file(dir.resolve("db"), clock = clock)

                refresh(listOf("x" to "Beta"))

                val afterA = assertNotNull(db.episodeDao().byId(a.id))
                val afterB = assertNotNull(db.episodeDao().byId(b.id))
                assertEquals(
                    a.enclosureUrl,
                    afterA.enclosureUrl,
                    "real fetch/parse/refresh must preserve Alpha's played row",
                )
                assertEquals("Alpha", afterA.title)
                assertEquals(
                    clock.now() - 1000,
                    assertNotNull(db.episodeStateDao().byEpisode(a.id)).playedAt,
                )
                assertTrue(assertNotNull(db.episodeStateDao().byEpisode(b.id)).isFavorite)
            } finally {
                db.close()
            }
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun leadingZeroRotationThroughFetchParserAndRestart() =
        runBlocking { preservesAcrossRealRefresh("127.000.000.001") }

    @Test
    fun trailingDotRotationThroughFetchParserAndRestart() = runBlocking { preservesAcrossRealRefresh("127.0.0.1.") }

    @Test
    fun decimalRotationThroughFetchParserAndRestartControl() = runBlocking { preservesAcrossRealRefresh("127.0.0.1") }
}
