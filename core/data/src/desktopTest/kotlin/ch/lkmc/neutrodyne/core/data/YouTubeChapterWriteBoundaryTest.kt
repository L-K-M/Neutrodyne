// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.playback.ChapterRepositoryImpl
import ch.lkmc.neutrodyne.core.data.playback.Podcasting20ChaptersParser
import ch.lkmc.neutrodyne.core.data.youtube.YouTubeDescriptionLoader
import ch.lkmc.neutrodyne.core.data.youtube.YouTubeFactsWriterImpl
import ch.lkmc.neutrodyne.core.database.DescriptionFormat
import ch.lkmc.neutrodyne.core.database.EpisodeDescriptionCodec
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.DescriptionCompleteness
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupShowNotesSanitizer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Hooks forward to the bundled driver and change state only inside the real write transaction. */
@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeChapterWriteBoundaryTest {
    @Test fun ownershipPriorityAndDurationAreRecheckedInsideActualWriteBoundary() =
        runTest {
            for (change in listOf("deleted", "video", "podcast", "rss", "duration", "priority")) {
                Fixture(this).use { f ->
                    val id = f.seed()
                    val other =
                        f.db.podcastDao().insertPodcast(
                            podcastEntity(feedUrl = "https://other.test/feed").copy(
                                sourceType = SourceType.YOUTUBE_CHANNEL,
                                youtubeChannelId = "UC" + "1".repeat(21) + "A",
                            ),
                        )
                    runCurrent() // Drain seed invalidations before arming the repository write boundary.
                    var fired = false
                    f.driver.armOnStep("BEGIN IMMEDIATE") {
                        fired = true
                        f.driver.exec(
                            when (change) {
                                "deleted" -> {
                                    "DELETE FROM episode WHERE id = $id"
                                }

                                "video" -> {
                                    "UPDATE episode SET externalMediaId = 'bbbbbbbbbbB' WHERE id = $id"
                                }

                                "podcast" -> {
                                    "UPDATE episode SET podcastId = $other WHERE id = $id"
                                }

                                "rss" -> {
                                    "UPDATE podcast SET sourceType = 'RSS'"
                                }

                                "duration" -> {
                                    "UPDATE episode SET durationMs = 29999 WHERE id = $id"
                                }

                                else -> {
                                    "INSERT INTO chapter (episodeId,source,ordinal,startMs,hidden) " +
                                        "VALUES ($id,'PSC',0,0,0)"
                                }
                            },
                        )
                    }
                    f.repo.ensureCurrentLoaded(id)
                    assertTrue(fired, "$change; virtual elapsed: ${testScheduler.currentTime}")
                    assertTrue(
                        f.db
                            .chapterDao()
                            .ofSource(id, ChapterSource.YOUTUBE_DESC)
                            .isEmpty(),
                        change,
                    )
                    assertTrue(f.engine.factCalls.isEmpty())
                }
            }
        }

    @Test fun cancellationDuringChapterInsertRollsBackAndStoredSnapshotRetries() =
        runTest {
            Fixture(this).use { f ->
                val id = f.seed()
                val original = f.db.episodeDao().byId(id)
                lateinit var occurrence: Job
                var fired = false
                f.driver.armOnStep("INSERT OR ABORT INTO `chapter`") {
                    fired = true
                    occurrence.cancel()
                }
                occurrence = launch(start = CoroutineStart.LAZY) { f.repo.ensureCurrentLoaded(id) }
                occurrence.start()
                occurrence.join()
                assertTrue(fired, "Insert hook not reached; virtual elapsed: ${testScheduler.currentTime}")
                assertTrue(occurrence.isCancelled)
                assertTrue(
                    f.db
                        .chapterDao()
                        .ofSource(id, ChapterSource.YOUTUBE_DESC)
                        .isEmpty(),
                )
                assertEquals(original, f.db.episodeDao().byId(id))
                f.repo.ensureCurrentLoaded(id)
                assertEquals(
                    3,
                    f.db
                        .chapterDao()
                        .ofSource(id, ChapterSource.YOUTUBE_DESC)
                        .size,
                )
                assertTrue(f.engine.factCalls.isEmpty())
            }
        }

    private class Fixture(
        scope: TestScope,
    ) : AutoCloseable {
        val driver = CommitCancellingDriver()
        private val clock = TestClock.from(scope.testScheduler)

        // Room must share virtual time with the loader deadline, as in the real facts fixture.
        val db =
            TestDb.inMemory(
                driver = driver,
                clock = clock,
                queryContext = StandardTestDispatcher(scope.testScheduler),
            )
        val engine = ControlledEnricher(clock)
        private val clients = OkHttpNeutrodyneHttpClients(newNetworkClients(), testUserAgent())
        private val writer = YouTubeFactsWriterImpl(db, JsoupShowNotesSanitizer())
        val repo =
            ChapterRepositoryImpl(
                db,
                Podcasting20ChaptersParser(clients),
                FakeNetworkMonitor(FakeNetworkMonitor.OFFLINE),
                clock,
                YouTubeDescriptionLoader(db, engine, writer, clock, scope.backgroundScope),
            )

        suspend fun seed(): Long {
            val podcast =
                db.podcastDao().insertPodcast(
                    podcastEntity().copy(
                        sourceType = SourceType.YOUTUBE_CHANNEL,
                        youtubeChannelId = "UC" + "0".repeat(21) + "A",
                    ),
                )
            val id =
                db.episodeDao().insertStub(
                    episodeEntity(podcastId = podcast).copy(externalMediaId = "aaaaaaaaaaA", durationMs = 30_000),
                )
            db.ingestDao().fillYouTubeDescription(
                id,
                EpisodeDescriptionCodec.encode(
                    "0:00 Intro\n0:10 Next\n0:20 End",
                    DescriptionFormat.PLAIN_TEXT,
                    DescriptionCompleteness.COMPLETE,
                ),
                "notes",
            )
            return id
        }

        override fun close() {
            clients.close()
            db.close()
        }
    }
}
