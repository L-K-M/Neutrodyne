// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshRequest
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.feeds.identity.UrlNormalizer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 03 Diff algorithm step 11 at the `FeedRefresher` boundary: the sync hook runs after an ingest
 * that inserted (or re-keyed) rows, with the committed episodes already visible; after a feed
 * move whose merge re-parented episodes into the surviving podcast even with zero inserts; and
 * never after an ingest that changed nothing — with no insert, rekey or re-parent, no committed
 * change can newly enable parked matching, so staying silent is honest (10 Parked state).
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

            // Same document again (forced, so it really fetches): no insert, no rekey, no hook.
            refresher.run(refreshRequest(force = true))
            assertEquals(listOf(id), hookCalls)
        }

    @Test
    fun theHookRunsAfterAnEnclosureRotationThatOnlyRekeys() =
        kotlinx.coroutines.test.runTest {
            val feedUrl = "https://r.example.com/f"
            val id =
                seedPodcast(
                    db,
                    feedUrl = feedUrl,
                    feedKey = UrlNormalizer.forIdentity(feedUrl)!!,
                    nextRefreshAt = 0L,
                )
            val hookCalls = mutableListOf<Long>()
            val hook = SyncIngestHook { podcastId -> hookCalls += podcastId }
            var token = "old"
            val refresher =
                newRefresher(
                    db,
                    mapOf(
                        SourceType.RSS to
                            stubAdapter(
                                onFetch = { _, _ ->
                                    adapterParsed(
                                        parsedFeed(
                                            items =
                                                listOf(
                                                    // No guid: the identity key derives from the
                                                    // enclosure, so rotating its token re-keys.
                                                    parsedEpisode(
                                                        0,
                                                        guid = null,
                                                        title = "Solo",
                                                        pubDate = 1_791_000_000_000L,
                                                        enclosureUrl = "https://cdn.example.com/ep.mp3?token=$token",
                                                    ),
                                                ),
                                        ),
                                    )
                                },
                            ),
                    ),
                    clock,
                    syncHook = hook,
                )

            refresher.run(refreshRequest())
            assertEquals(listOf(id), hookCalls)
            val inserted = db.ingestDao().existing(id).single()

            // The same episode under a rotated enclosure token: no insert, one rekey, one hook.
            token = "new"
            refresher.run(refreshRequest(force = true))
            assertEquals(listOf(id, id), hookCalls)
            assertEquals(
                inserted.id,
                db
                    .ingestDao()
                    .existing(id)
                    .single()
                    .id,
            )
        }

    @Test
    fun aMergedMoveFiresTheHookForTheSurvivorEvenWithZeroInserts() =
        kotlinx.coroutines.test.runTest {
            val survivorUrl = "https://a.example.com/feed"
            val survivorId =
                seedPodcast(
                    db,
                    feedUrl = survivorUrl,
                    feedKey = UrlNormalizer.forIdentity(survivorUrl)!!,
                    nextRefreshAt = null,
                )
            val movingUrl = "https://b.example.com/feed"
            val movingId =
                seedPodcast(
                    db,
                    feedUrl = movingUrl,
                    feedKey = UrlNormalizer.forIdentity(movingUrl)!!,
                    nextRefreshAt = 0L,
                )
            db.ingestDao().insertEpisodes(listOf(episodeEntity(podcastId = survivorId, identityKey = "g:one")))
            db.ingestDao().insertEpisodes(listOf(episodeEntity(podcastId = survivorId, identityKey = "g:two")))
            // g:three lives only on the moving feed: the merge re-parents it into the survivor.
            db.ingestDao().insertEpisodes(listOf(episodeEntity(podcastId = movingId, identityKey = "g:three")))

            val hookCalls = mutableListOf<Long>()
            var threeVisibleInHook = false
            val hook =
                SyncIngestHook { podcastId ->
                    hookCalls += podcastId
                    threeVisibleInHook =
                        db.episodeDao().byIdentityKey(podcastId, "g:three") != null
                }
            val items =
                listOf(
                    parsedEpisode(0, guid = "one", enclosureUrl = "https://cdn.example.com/one.mp3"),
                    parsedEpisode(1, guid = "two", enclosureUrl = "https://cdn.example.com/two.mp3"),
                    parsedEpisode(2, guid = "three", enclosureUrl = "https://cdn.example.com/three.mp3"),
                )
            val adapter =
                stubAdapter(
                    onFetch = { feed, _ ->
                        if (feed.id != movingId) return@stubAdapter null
                        adapterParsed(
                            parsedFeed(items = items),
                            meta =
                                fetchMeta(
                                    finalUrl = movingUrl,
                                    requestedUrl = movingUrl,
                                    permanentUrl = survivorUrl,
                                ),
                        )
                    },
                )
            val refresher = newRefresher(db, mapOf(SourceType.RSS to adapter), clock, syncHook = hook)

            val report = refresher.run(refreshRequest())

            // The moving feed merged into the survivor; its unique episode was re-parented and
            // the retry ingest into the survivor inserted nothing — only the merge moved rows.
            assertEquals(
                ch.lkmc.neutrodyne.core.data.refresh.FeedOutcome
                    .Merged(survivorId),
                report.outcomes[movingId],
            )
            assertEquals(listOf(survivorId), hookCalls)
            assertEquals(true, threeVisibleInHook)
        }

    private fun refreshRequest(force: Boolean = false) =
        RefreshRequest(
            scope = ch.lkmc.neutrodyne.core.domain.RefreshScope.All,
            force = force,
            pagesOnly = false,
            origin = RefreshOrigin.PERIODIC,
        )
}
