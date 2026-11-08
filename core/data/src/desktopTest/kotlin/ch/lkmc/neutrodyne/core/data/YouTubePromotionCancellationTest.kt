// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.refresh.AdapterResult
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshRequest
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class YouTubePromotionCancellationTest {
    @Test fun refreshDeadlineAfterPromotionCommitStillEmitsAvailableNewItem() =
        runTest {
            val clock = TestClock()
            val db = newDb(clock, StandardTestDispatcher(testScheduler))
            try {
                val channel =
                    seedPodcast(db, "https://www.youtube.com/feeds/videos.xml?channel_id=UCaaaaaaaaaaaaaaaaaaaaaA") {
                        copy(sourceType = SourceType.YOUTUBE_CHANNEL, youtubeChannelId = "UCaaaaaaaaaaaaaaaaaaaaaA")
                    }
                val id =
                    db.episodeDao().insertStub(
                        episodeEntity(podcastId = channel) {
                            copy(
                                externalMediaId = "aaaaaaaaaaA",
                                availability = Availability.UPCOMING,
                                isNew = true,
                                firstSeenAt = clock.now(),
                                sortDate = clock.now(),
                            )
                        },
                    )
                val adapter =
                    stubAdapter(
                        onFetch = { _, _ -> AdapterResult.Unchanged(fetchMeta()) },
                        announce = { _, _, _ ->
                            db.episodeDao().setAvailability(id, Availability.AVAILABLE)
                            awaitCancellation()
                        },
                    )
                val refresher = newRefresher(db, mapOf(SourceType.YOUTUBE_CHANNEL to adapter), clock)
                val report =
                    refresher.run(
                        RefreshRequest(
                            RefreshScope.Podcasts(listOf(channel)),
                            true,
                            false,
                            RefreshOrigin.MANUAL,
                            deadlineElapsedMs = 60_000,
                        ),
                    )
                assertEquals(Availability.AVAILABLE, db.episodeDao().byId(id)!!.availability)
                assertEquals(listOf(id), report.newEpisodes.flatMap { it.episodeIds })
            } finally {
                db.close()
            }
        }
}
