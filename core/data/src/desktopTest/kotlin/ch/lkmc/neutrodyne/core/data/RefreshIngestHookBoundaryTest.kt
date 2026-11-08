// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshRequest
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 03 Diff algorithm step 11 at the `FeedRefresher` boundary: the sync hook runs after an ingest
 * that inserted (or re-keyed) rows, with the committed episodes already visible, and never after
 * an ingest that changed nothing — a zero-insert ingest cannot newly enable parked matching, so
 * staying silent is honest (10 Parked state and stubs).
 */
class RefreshIngestHookBoundaryTest {
    private val clock = TestClock()
    private val db = newDb(clock)

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun theHookRunsAfterAnInsertingIngestAndNotAfterAnUnchangedOne() =
        kotlinx.coroutines.test.runTest {
            val feedUrl = "https://a.example.com/f"
            val id =
                seedPodcast(
                    db,
                    feedUrl = feedUrl,
                    feedKey = UrlNormalizer.forIdentity(feedUrl)!!,
                    nextRefreshAt = 0L,
                )
            val hookCalls = mutableListOf<Long>()
            var episodesVisibleInHook = -1
            val hook =
                SyncIngestHook { podcastId ->
                    hookCalls += podcastId
                    episodesVisibleInHook = db.podcastDao().episodeCount(podcastId)
                }
            val item = parsedEpisode(0, guid = "g-1")
            val adapter = stubAdapter(onFetch = { _, _ -> adapterParsed(parsedFeed(items = listOf(item))) })
            val refresher = newRefresher(db, mapOf(SourceType.RSS to adapter), clock, syncHook = hook)

            refresher.run(refreshRequest())

            assertEquals(listOf(id), hookCalls)
            assertEquals(1, episodesVisibleInHook)

            // Same document again: no insert, no rekey, no hook.
            refresher.run(refreshRequest())
            assertEquals(listOf(id), hookCalls)
        }

    private fun refreshRequest() =
        RefreshRequest(
            scope = ch.lkmc.neutrodyne.core.domain.RefreshScope.All,
            force = false,
            pagesOnly = false,
            origin = RefreshOrigin.PERIODIC,
        )
}
