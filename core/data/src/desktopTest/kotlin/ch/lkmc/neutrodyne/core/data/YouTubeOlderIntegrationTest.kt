// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.data

import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.data.youtube.YouTubeAvailabilityRecorderImpl
import ch.lkmc.neutrodyne.core.data.youtube.YouTubeChannelRepositoryImpl
import ch.lkmc.neutrodyne.core.data.youtube.YouTubeOlderLoader
import ch.lkmc.neutrodyne.core.domain.LoadOlderResult
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.testing.FakeSearchRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeChannelMetadata
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.api.UploadsCursor
import ch.lkmc.neutrodyne.youtube.api.UploadsPageResult
import ch.lkmc.neutrodyne.youtube.api.VideoFactsResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubeOlderIntegrationTest {
    @Test fun olderPagesPreserveAtomRowsAndHaveNoNewAbsenceOrPodcastWrites() =
        runTest {
            FactsFixture(this).use { f ->
                val channel = f.channel(variants = 7)
                val existingId = f.episode(channel, VIDEO)
                val oldId = f.episode(channel, THIRD, age = 90 * DAY)
                val before = f.db.episodeDao().byId(existingId)!!
                val podcast = f.db.podcastDao().byId(channel)!!
                val page =
                    listOf(
                        fact(VIDEO, 300, Availability.PRIVATE).copy(title = "Stale title"),
                        fact(OTHER, 200, Availability.AVAILABLE).copy(
                            title = "Older",
                            publishedAtMs =
                                f.clock.now() - 3 * DAY + 1234,
                            publishedApprox = true,
                        ),
                        fact("ddddddddddD", 100, Availability.LIVE),
                    )
                f.engine.page = { _, _, _ -> UploadsPageResult.Ok(page, UploadsCursor("next")) }
                val loader = f.older()
                assertEquals(LoadOlderResult.Loaded(2, true), loader.load(channel))
                assertEquals(before, f.db.episodeDao().byId(existingId))
                assertEquals(podcast, f.db.podcastDao().byId(channel))
                assertTrue(
                    f.db
                        .episodeDao()
                        .byId(oldId)!!
                        .inFeed,
                )
                val inserted = f.db.episodeDao().youTubeByVideoId(channel, OTHER)!!
                val missingDate = f.db.episodeDao().youTubeByVideoId(channel, "ddddddddddD")!!
                assertFalse(inserted.isNew)
                assertEquals("approx", inserted.rawPubDate)
                assertEquals(0, inserted.pubDate!! % DAY)
                assertEquals(inserted.pubDate, missingDate.pubDate)
                assertEquals(200, inserted.durationMs)
                assertEquals(listOf(1), f.engine.pages.map { it.second })
                assertTrue(
                    f.db
                        .downloadDao()
                        .downloadedEpisodeIds(listOf(channel))
                        .isEmpty(),
                )
            }
        }

    @Test fun expiredRestartAndDuplicatePagesSkipForwardUnderProcessCap() =
        runTest {
            FactsFixture(this).use { f ->
                val channel = f.channel(variants = 2)
                f.episode(channel, VIDEO)
                var calls = 0
                f.engine.page = { _, _, cursor ->
                    calls++
                    when (calls) {
                        1 -> {
                            UploadsPageResult.Ok(listOf(fact(OTHER)), UploadsCursor("expired"))
                        }

                        2 -> {
                            assertEquals(UploadsCursor("expired"), cursor)
                            UploadsPageResult.Ok(listOf(fact(VIDEO)), UploadsCursor("restart"))
                        }

                        3 -> {
                            UploadsPageResult.Ok(listOf(fact(OTHER)), UploadsCursor("known"))
                        }

                        else -> {
                            UploadsPageResult.Ok(listOf(fact(THIRD)), null)
                        }
                    }
                }
                val loader = f.older()
                assertEquals(LoadOlderResult.Loaded(1, true), loader.load(channel))
                assertEquals(LoadOlderResult.Loaded(1, false), loader.load(channel))
                assertEquals(4, calls)
                assertTrue(
                    f.db
                        .episodeDao()
                        .youTubeByVideoId(channel, THIRD)!!
                        .isShort,
                )
                assertEquals(LoadOlderResult.Loaded(0, false), loader.load(channel))
                assertEquals(4, calls)
                assertTrue(f.engine.pages.all { it.second == 2 })
            }
        }

    @Test fun twentyPageCapSurvivesVariantChangesAndRepeatedKnownPages() =
        runTest {
            FactsFixture(this).use { f ->
                val channel = f.channel()
                f.episode(channel)
                f.engine.page = { _, _, _ -> UploadsPageResult.Ok(listOf(fact(VIDEO)), UploadsCursor("again")) }
                val loader = f.older()
                assertEquals(LoadOlderResult.Loaded(0, false), loader.load(channel))
                assertEquals(20, f.engine.pages.size)
                loader.setVariants(channel, 3)
                assertEquals(LoadOlderResult.Loaded(0, false), loader.load(channel))
                assertEquals(20, f.engine.pages.size)
            }
        }

    @Test fun samePodcastLoadsCoalesceAndOneCancelledWaiterKeepsOther() =
        runTest {
            FactsFixture(this).use { f ->
                val channel = f.channel()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                f.engine.page =
                    { _, _, _ ->
                        entered.complete(Unit)
                        release.await()
                        UploadsPageResult.Ok(listOf(fact(VIDEO)), null)
                    }
                val loader = f.older()
                val a = async { loader.load(channel) }
                entered.await()
                val b = async { loader.load(channel) }
                delay(100)
                a.cancelAndJoin()
                release.complete(Unit)
                assertEquals(LoadOlderResult.Loaded(1, false), b.await())
                assertEquals(1, f.engine.pages.size)
            }
        }

    @Test fun variantChangeCancelsFlightAndRejectsNonCooperativeLateReply() =
        runTest {
            FactsFixture(this).use { f ->
                val channel = f.channel()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                f.engine.page =
                    {
                        _,
                        _,
                        _,
                        ->
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        UploadsPageResult.Ok(listOf(fact(VIDEO)), null)
                    }
                val loader = f.older()
                val load = async { loader.load(channel) }
                entered.await()
                loader.setVariants(channel, 3)
                release.complete(Unit)
                load.join()
                assertTrue(load.isCancelled)
                assertTrue(
                    f.db
                        .ingestDao()
                        .existing(channel)
                        .isEmpty(),
                )
                f.engine.page = { _, _, cursor ->
                    assertNull(cursor)
                    UploadsPageResult.Ok(listOf(fact(OTHER)), null)
                }
                assertEquals(LoadOlderResult.Loaded(1, false), loader.load(channel))
            }
        }

    @Test fun cancelledLastWaiterAndDeletionPreventLateInsert() =
        runTest {
            FactsFixture(this).use { f ->
                val channel = f.channel()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                f.engine.page =
                    { _, _, _ ->
                        entered.complete(Unit)
                        release.await()
                        UploadsPageResult.Ok(listOf(fact(VIDEO)), null)
                    }
                val loader = f.older()
                val load = async { loader.load(channel) }
                entered.await()
                load.cancelAndJoin()
                release.complete(Unit)
                assertTrue(
                    f.db
                        .ingestDao()
                        .existing(channel)
                        .isEmpty(),
                )
            }
        }

    @Test fun lastWaiterCanCancelWhilePageTransactionIsSuspended() =
        runTest {
            FactsFixture(this).use { f ->
                val channel = f.channel()
                f.engine.page = { _, _, _ -> UploadsPageResult.Ok(listOf(fact(VIDEO)), null) }
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val writer =
                    object : ch.lkmc.neutrodyne.core.domain.YouTubeFactsWriter by f.writer {
                        override suspend fun applyFacts(
                            podcastId: Long,
                            facts: List<ch.lkmc.neutrodyne.youtube.api.VideoFacts>,
                        ) {
                            entered.complete(Unit)
                            release.await()
                            f.writer.applyFacts(podcastId, facts)
                        }
                    }
                val loader =
                    YouTubeOlderLoader(
                        f.db,
                        f.engine,
                        newIngestor(
                            f.db,
                            f.clock,
                            defaultDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
                        ),
                        writer,
                        f.clock,
                        backgroundScope,
                    )
                val load = async { loader.load(channel) }
                entered.await()
                val cancellation = async { load.cancelAndJoin() }
                delay(100)
                val cancelledBeforeStorageResumed = cancellation.isCompleted
                release.complete(Unit)
                cancellation.await()
                assertTrue(cancelledBeforeStorageResumed)
                assertTrue(
                    f.db
                        .ingestDao()
                        .existing(channel)
                        .isEmpty(),
                )
            }
        }

    @Test fun deletedChannelAndExpiredDeadlineCannotApplyLatePages() =
        runTest {
            FactsFixture(this).use { f ->
                val channel = f.channel()
                val ready = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                f.engine.page = { _, _, _ ->
                    ready.complete(Unit)
                    finish.await()
                    UploadsPageResult.Ok(listOf(fact(VIDEO)), null)
                }
                val loader = f.older()
                val load = async { loader.load(channel) }
                ready.await()
                f.db.useWriterConnection { it.execSql("DELETE FROM podcast WHERE id = $channel") }
                finish.complete(Unit)
                assertIs<LoadOlderResult.Failed>(load.await())
                assertTrue(
                    f.db
                        .ingestDao()
                        .existing(channel)
                        .isEmpty(),
                )
                val timed = f.channel(1)
                f.engine.page = { _, _, _ ->
                    withContext(NonCancellable) { delay(46_000) }
                    UploadsPageResult.Ok(listOf(fact(VIDEO)), null)
                }
                assertEquals(LoadOlderResult.Failed(TransientKind.TIMEOUT), loader.load(timed))
                assertTrue(
                    f.db
                        .ingestDao()
                        .existing(timed)
                        .isEmpty(),
                )
            }
        }

    @Test fun failedAndMalformedPagesDoNotAdvanceCursorOrInsertAndCanRecover() =
        runTest {
            FactsFixture(this).use { f ->
                val channel = f.channel()
                val loader = f.older()
                f.engine.page = { _, _, _ -> UploadsPageResult.Failed(TransientKind.NETWORK) }
                assertEquals(LoadOlderResult.Failed(TransientKind.NETWORK), loader.load(channel))
                f.engine.page = { _, _, _ -> UploadsPageResult.Ok(listOf(fact("bad")), null) }
                assertIs<LoadOlderResult.Failed>(loader.load(channel))
                assertTrue(
                    f.db
                        .ingestDao()
                        .existing(channel)
                        .isEmpty(),
                )
                f.engine.page = { _, _, cursor ->
                    assertNull(cursor)
                    UploadsPageResult.Ok(listOf(fact(VIDEO)), null)
                }
                assertEquals(LoadOlderResult.Loaded(1, false), loader.load(channel))
            }
        }

    @Test fun repositoryCheckAgainUsesFactsAndOneRecorderAndTransientWritesNothing() =
        runTest {
            FactsFixture(this).use { f ->
                val channel = f.channel()
                val id = f.episode(channel, availability = Availability.PRIVATE)
                val scheduler = FakeRefreshScheduler()
                val repository =
                    YouTubeChannelRepositoryImpl(
                        f.db,
                        FakeYouTubeChannelMetadata(),
                        scheduler,
                        FakeSearchRepository(),
                        FakeSettingsRepository(),
                        f.older(),
                        f.engine,
                        YouTubeAvailabilityRecorderImpl(f.db),
                        f.enrichment,
                    )
                val before = f.db.episodeDao().byId(id)!!
                f.engine.facts = { VideoFactsResult.Failed(TransientKind.NETWORK, null) }
                assertNull(repository.recheckAvailability(id))
                assertEquals(before, f.db.episodeDao().byId(id))
                f.engine.facts = { VideoFactsResult.Ok(fact(it, availability = Availability.AVAILABLE)) }
                assertEquals(Availability.AVAILABLE, repository.recheckAvailability(id))
                assertEquals(before.copy(availability = Availability.AVAILABLE), f.db.episodeDao().byId(id))
                assertEquals(listOf(VIDEO, VIDEO), f.engine.factCalls)
            }
        }

    private fun FactsFixture.older() =
        YouTubeOlderLoader(
            db,
            engine,
            newIngestor(
                db,
                clock,
                defaultDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(scope.testScheduler),
            ),
            writer,
            clock,
            scope.backgroundScope,
        )

    private companion object {
        const val VIDEO = "aaaaaaaaaaA"
        const val OTHER = "bbbbbbbbbbB"
        const val THIRD = "ccccccccccC"
        const val DAY = 86_400_000L
    }
}
