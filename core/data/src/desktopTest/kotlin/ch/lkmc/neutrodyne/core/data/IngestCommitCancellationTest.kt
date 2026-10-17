// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.notifications.NewEpisodeNotifier
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshRequest
import ch.lkmc.neutrodyne.core.data.settings.EffectiveSettingsResolverImpl
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeNewEpisodePoster
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * 03 Engine run step 10: feeds that committed still notify after a worker stop or a desktop quit.
 * Room re-checks cancellation as the ingest transaction returns, so a stop landing at COMMIT must
 * not drop the committed episodes' announcement (main review at 4919b3a7, F3).
 */
class IngestCommitCancellationTest {
    private val clock = TestClock()
    private val driver = CommitCancellingDriver()
    private val db = TestDb.inMemory(driver = driver, clock = clock)
    private val settings = FakeSettingsRepository()
    private val poster = FakeNewEpisodePoster()

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun aRunCancelledAtTheIngestCommitStillPostsTheCommittedEpisode() =
        runTest {
            settings.set(FeedsSettingKeys.NOTIFY_NEW_EPISODES, true)
            val podcast = seedPodcast(db, "https://a.example.com/f", subscribedAt = clock.now() - 30 * DAY)
            val adapter =
                stubAdapter(onFetch = { _, _ ->
                    adapterParsed(
                        parsedFeed(items = listOf(parsedEpisode(0, guid = "committed", pubDate = clock.now()))),
                    )
                })
            val engine =
                newRefresher(
                    db,
                    mapOf(SourceType.RSS to adapter),
                    clock,
                    settings,
                    notifier = NewEpisodeNotifier(db, EffectiveSettingsResolverImpl(db, settings), poster),
                )

            lateinit var run: Job
            run =
                launch(start = CoroutineStart.LAZY) {
                    engine.run(RefreshRequest(RefreshScope.All, false, false, RefreshOrigin.PERIODIC))
                }
            driver.arm("INSERT OR ABORT INTO `episode`") { run.cancel() }
            run.start()
            run.join()

            assertEquals(1, driver.cancellations, "the commit hook fired")
            val episode = assertNotNull(db.episodeDao().byIdentityKey(podcast, "g:committed"))
            assertFalse(engine.status.value.running)
            assertEquals(
                listOf(episode.id),
                poster.posts
                    .flatten()
                    .flatMap { it.episodes }
                    .map { it.episodeId },
            )
        }

    private companion object {
        const val DAY = 86_400_000L
    }
}
