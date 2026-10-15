// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.data.refresh.AdapterResult
import ch.lkmc.neutrodyne.core.data.refresh.FeedOutcome
import ch.lkmc.neutrodyne.core.data.refresh.NewFeedUrlProbe
import ch.lkmc.neutrodyne.core.data.refresh.RefreshRequest
import ch.lkmc.neutrodyne.core.database.DueFeed
import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.feeds.identity.PodcastGuid
import ch.lkmc.neutrodyne.feeds.model.ParsedFeed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class NewFeedUrlProbeTest {
    private val clock = TestClock()
    private val db = newDb(clock)
    private val probe = NewFeedUrlProbe(db, clock)
    private val current = parsedFeed(items = listOf(parsedEpisode(order = 0, guid = "shared")))
    private val attempts = mutableListOf<String>()
    private val adopted = mutableListOf<String>()

    /** A fetched, parsed target that fails every plausibility rule — what the rejection LRU holds. */
    private val implausible = parsedFeed(title = "Unrelated", items = listOf(parsedEpisode(order = 0, guid = "other")))

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun seed(target: String = TARGET): Long =
        seedPodcast(db, SOURCE, block = {
            copy(pendingNewFeedUrl = target)
        })

    private fun candidate(
        url: String,
        feed: ParsedFeed = current,
    ) = AdapterResult.Parsed(feed, false, fetchMeta(finalUrl = url))

    private suspend fun run(
        id: Long,
        evidence: ParsedFeed = current,
        deadline: Long = RefreshRequest.NO_DEADLINE,
        fetch: suspend (DueFeed) -> AdapterResult = { candidate(it.feedUrl, implausible) },
        adopt: suspend (DueFeed, String, AdapterResult.Parsed) -> FeedOutcome = { feed, url, result ->
            adopted += url
            db.podcastDao().setPendingNewFeedUrl(feed.id, result.feed.newFeedUrl)
            FeedOutcome.Ingested(0, 0, false, null)
        },
    ): FeedOutcome? =
        probe.run(id, evidence, deadline, fetch = { feed ->
            attempts += feed.feedUrl
            assertNull(feed.etag)
            assertNull(feed.lastModified)
            assertNull(feed.contentSha256)
            fetch(feed)
        }, adopt = adopt)

    @Test
    fun failureRetainsPendingWithoutRejecting() =
        runTest {
            // A failed or faulted fetch says nothing about the target (03: the LRU holds
            // implausible targets), so the next probe tries it again.
            val id = seed()
            assertNull(run(id, fetch = { failed() }))
            assertNull(run(id, fetch = { throw IOException("connection reset") }))
            assertNull(run(id))
            assertEquals(listOf(TARGET, TARGET, TARGET), attempts)
            assertEquals(TARGET, db.podcastDao().byId(id)?.pendingNewFeedUrl)
            assertTrue(adopted.isEmpty())
        }

    @Test
    fun implausibleTargetIsRejectedForRepeats() =
        runTest {
            val id = seed()
            assertNull(run(id))
            assertNull(run(id))
            assertEquals(listOf(TARGET), attempts)
            assertEquals(TARGET, db.podcastDao().byId(id)?.pendingNewFeedUrl)
        }

    @Test
    fun implausibleAndNoMediaTargetsRemainPending() =
        runTest {
            for (body in listOf(
                parsedFeed(title = "Unrelated", items = listOf(parsedEpisode(order = 0, guid = "other"))),
                parsedFeed(),
            )) {
                val id = seed()
                assertNull(run(id, fetch = { candidate(it.feedUrl, body) }))
                assertEquals(TARGET, db.podcastDao().byId(id)?.pendingNewFeedUrl)
                db.podcastDao().deleteCascade(id, clock.now())
                clock.advanceBy(24.hours)
            }
            assertTrue(adopted.isEmpty())
        }

    @Test
    fun rejectionExpiresAtTwentyFourHoursWithoutSliding() =
        runTest {
            val id = seed()
            run(id)
            clock.advanceBy(23.hours)
            run(id)
            assertEquals(1, attempts.size)
            clock.advanceBy(1.hours)
            run(id)
            assertEquals(2, attempts.size)
        }

    @Test
    fun rejectionLruKeepsOneHundredUrlsAndTouchesReads() =
        runTest {
            val id = seed("https://example.com/target0")
            for (index in 0 until 100) {
                db.podcastDao().setPendingNewFeedUrl(id, "https://example.com/target$index")
                run(id)
            }
            db.podcastDao().setPendingNewFeedUrl(id, "https://example.com/target0")
            run(id)
            db.podcastDao().setPendingNewFeedUrl(id, "https://example.com/target100")
            run(id)
            db.podcastDao().setPendingNewFeedUrl(id, "https://example.com/target0")
            run(id)
            assertEquals(101, attempts.size)
            db.podcastDao().setPendingNewFeedUrl(id, "https://example.com/target1")
            run(id)
            assertEquals(102, attempts.size)
        }

    @Test
    fun identityEquivalentTargetsUseOneRejectionEntry() =
        runTest {
            val id = seed("https://EXAMPLE.com:443/target")
            run(id)
            db.podcastDao().setPendingNewFeedUrl(id, TARGET)
            run(id)
            assertEquals(1, attempts.size)
        }

    @Test
    fun repeatedAnnouncementStopsTheChain() =
        runTest {
            val id = seed()
            run(id, fetch = { candidate(it.feedUrl, current.copy(newFeedUrl = TARGET)) })
            assertEquals(listOf(TARGET), attempts)
            assertEquals(listOf(TARGET), adopted)
        }

    @Test
    fun announcementBackToSourceNeverFetches() =
        runTest {
            val id = seed(SOURCE)
            run(id, fetch = { error("loop fetched") })
            assertTrue(attempts.isEmpty())
        }

    @Test
    fun fiveHopCapRetainsSixthAnnouncement() =
        runTest {
            val id = seed("https://example.com/target1")
            run(id, fetch = { feed ->
                val next = feed.feedUrl.substringAfterLast("target").toInt() + 1
                candidate(feed.feedUrl, current.copy(newFeedUrl = "https://example.com/target$next"))
            })
            assertEquals(5, attempts.size)
            assertEquals(5, adopted.size)
            assertEquals("https://example.com/target6", db.podcastDao().byId(id)?.pendingNewFeedUrl)
        }

    @Test
    fun deadlineMarginStopsBeforeFetchingAndLeavesPending() =
        runTest {
            val id = seed()
            run(id, deadline = clock.elapsedRealtime() + 30_000)
            assertTrue(attempts.isEmpty())
            assertEquals(TARGET, db.podcastDao().byId(id)?.pendingNewFeedUrl)
        }

    @Test
    fun deadlineStopsBetweenHops() =
        runTest {
            val id = seed()
            run(id, deadline = 60_000, fetch = {
                candidate(
                    it.feedUrl,
                    current.copy(newFeedUrl = "$TARGET/next"),
                )
            }, adopt = {
                feed,
                url,
                result,
                ->
                adopted += url
                db.podcastDao().setPendingNewFeedUrl(feed.id, result.feed.newFeedUrl)
                clock.elapsedMs = 30_000
                FeedOutcome.Ingested(0, 0, false, null)
            })
            assertEquals(1, attempts.size)
            assertEquals("$TARGET/next", db.podcastDao().byId(id)?.pendingNewFeedUrl)
        }

    @Test
    fun cancellationPropagatesAndDoesNotRejectPendingTarget() =
        runTest {
            val id = seed()
            val fetching = CompletableDeferred<Unit>()
            val job =
                async {
                    run(id, fetch = {
                        fetching.complete(Unit)
                        awaitCancellation()
                    })
                }
            fetching.await()
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(TARGET, db.podcastDao().byId(id)?.pendingNewFeedUrl)
            assertFalse(run(id, fetch = { candidate(it.feedUrl) }) == null)
            assertEquals(2, attempts.size)
        }

    @Test
    fun storedDerivedGuidNeverMakesTargetPlausible() =
        runTest {
            val guid = PodcastGuid.derive(SOURCE)
            val id =
                seedPodcast(
                    db,
                    SOURCE,
                    block = { copy(pendingNewFeedUrl = TARGET, podcastGuid = guid, podcastGuidDerived = true) },
                )
            run(id, evidence = parsedFeed(title = "Left", podcastGuid = guid), fetch = {
                candidate(
                    it.feedUrl,
                    parsedFeed(
                        title = "Right",
                        podcastGuid = guid,
                        items = listOf(parsedEpisode(order = 0, guid = "other")),
                    ),
                )
            })
            assertTrue(adopted.isEmpty())
            assertEquals(TARGET, db.podcastDao().byId(id)?.pendingNewFeedUrl)
        }

    @Test
    fun storedRealGuidCanMatchWhenCurrentBodyOmitsIt() =
        runTest {
            val guid = "12345678-1234-1234-1234-123456789abc"
            val id =
                seedPodcast(
                    db,
                    SOURCE,
                    block = { copy(pendingNewFeedUrl = TARGET, podcastGuid = guid, podcastGuidDerived = false) },
                )
            run(id, evidence = parsedFeed(title = "Left"), fetch = {
                candidate(
                    it.feedUrl,
                    parsedFeed(
                        title = "Right",
                        podcastGuid = guid,
                        items = listOf(parsedEpisode(order = 0, guid = "other")),
                    ),
                )
            })
            assertEquals(listOf(TARGET), adopted)
        }

    @Test
    fun deadlineAfterFetchDoesNotAdoptOrReject() =
        runTest {
            val id = seed()
            run(id, deadline = 60_000, fetch = {
                clock.elapsedMs = 60_000
                candidate(it.feedUrl)
            })
            assertTrue(adopted.isEmpty())
            assertEquals(TARGET, db.podcastDao().byId(id)?.pendingNewFeedUrl)
            run(id, fetch = { candidate(it.feedUrl) })
            assertEquals(listOf(TARGET), adopted)
        }

    @Test
    fun collisionSurvivorAlreadyProbedCannotStartAnotherChain() =
        runTest {
            val id = seed()
            val survivor =
                seedPodcast(db, "https://example.com/survivor", block = {
                    copy(pendingNewFeedUrl = "https://example.com/next")
                })
            val result =
                probe.run(
                    id,
                    current,
                    RefreshRequest.NO_DEADLINE,
                    fetch = {
                        attempts += it.feedUrl
                        candidate(it.feedUrl)
                    },
                    adopt = { _, _, _ -> FeedOutcome.Merged(survivor) },
                    claimSurvivor = { false },
                )
            assertEquals(FeedOutcome.Merged(survivor), result)
            assertEquals(listOf(TARGET), attempts)
            assertEquals("https://example.com/next", db.podcastDao().byId(survivor)?.pendingNewFeedUrl)
        }

    private fun failed() = AdapterResult.Failed(FeedErrorKind.HTTP_NOT_FOUND, 404, null, true)

    private companion object {
        const val SOURCE = "https://example.com/source"
        const val TARGET = "https://example.com/target"
    }
}
