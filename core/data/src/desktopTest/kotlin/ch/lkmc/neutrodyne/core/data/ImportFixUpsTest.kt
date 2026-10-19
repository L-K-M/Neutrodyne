// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.importing.ImportFixUps
import ch.lkmc.neutrodyne.core.data.importing.ImportOptionsJson
import ch.lkmc.neutrodyne.core.data.importing.ImportWorkPolicy
import ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.domain.UnsubscribeUseCase
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.ChangeOrigin
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.testing.FakeNewEpisodePoster
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ImportFixUps` (05 §8) against a real Room database: Retry clears the podcast block through
 * `PodcastRepository.retry`, requeues the still-eligible rows, bumps `attemptMarkerAt` and
 * enqueues `APPEND_OR_REPLACE`; Edit URL / Enter password route through `editFeedUrl` /
 * `setCredentials`; Remove unsubscribes only session-owned podcasts and then deletes the
 * rows — `ALREADY_SUBSCRIBED`, merge-repointed and foreign rows are never touched.
 */
class ImportFixUpsTest {
    private val clock = TestClock()
    private val driver = CommitCancellingDriver()
    private val db = TestDb.inMemory(driver = driver, clock = clock)
    private val scheduler = FakeImportWorkScheduler()
    private val podcasts = CascadingPodcasts()
    private val fixUps =
        ImportFixUps(
            db = db,
            podcasts = podcasts,
            unsubscribe = UnsubscribeUseCase(podcasts, FakeNewEpisodePoster()),
            workScheduler = scheduler,
            clock = clock,
        )

    @AfterTest
    fun tearDown() {
        db.close()
    }

    // --- Retry -------------------------------------------------------------------------------------

    @Test
    fun unresolvedChannelsSupportRetryEditAndRemoveWithoutPodcastRows() =
        runTest {
            val sid =
                db.seedImportSession(
                    state = ImportState.DONE,
                    options =
                        ImportOptions(
                            itemYouTubeInputs = mapOf(1 to "@old"),
                            itemYouTubeVariants = mapOf(1 to 6),
                        ),
                    items =
                        listOf(
                            importItem(
                                1,
                                "@old",
                                normalizedUrl = null,
                                kind = ImportItemKind.YOUTUBE,
                                status = ImportItemStatus.FETCH_FAILED,
                                errorDetail = "YOUTUBE_RESOLUTION_FAILED",
                            ),
                        ),
                )
            fixUps.retry(sid, setOf(1))
            assertEquals(
                ImportItemStatus.QUEUED,
                db
                    .importDao()
                    .itemsOf(sid)
                    .single()
                    .status,
            )
            assertEquals(ImportState.FETCHING, db.importDao().sessionById(sid)!!.state)
            assertIs<Outcome.Failure<*>>(fixUps.editUrl(sid, 1, "https://example.test/not-youtube"))
            assertIs<Outcome.Success<*>>(fixUps.editUrl(sid, 1, "@new"))
            assertEquals("@new", optionsOf(sid).itemYouTubeInputs[1])
            assertEquals(6, optionsOf(sid).itemYouTubeVariants[1])
            assertTrue(podcasts.inner.calls.isEmpty())
            fixUps.remove(sid, setOf(1))
            assertTrue(db.importDao().itemsOf(sid).isEmpty())
            assertTrue(db.podcastDao().allForExport().isEmpty())
        }

    @Test
    fun retryClearsBlocksRequeuesAndSchedulesAppend() =
        runTest {
            val goneId = seedPodcast(db, "https://gone.example.com/f", gone = true)
            val failedId = seedPodcast(db, "https://failed.example.com/f")
            val sid =
                db.seedImportSession(
                    state = ImportState.FETCHING,
                    options = ImportOptions(committedAt = 1, attemptMarkerAt = 5),
                    items =
                        listOf(
                            importItem(
                                1,
                                "https://gone.example.com/f",
                                status = ImportItemStatus.GONE,
                                podcastId = goneId,
                            ),
                            importItem(
                                2,
                                "https://failed.example.com/f",
                                status = ImportItemStatus.FETCH_FAILED,
                                podcastId = failedId,
                                errorDetail = "HTTP_500",
                            ),
                        ),
                )
            clock.nowMs = 50

            fixUps.retry(sid, setOf(1, 2))

            assertEquals(
                listOf("retry($goneId, false)", "retry($failedId, false)"),
                podcasts.inner.calls,
                "the refresh block clears through the domain path without a manual refresh",
            )
            val items = db.importDao().itemsOf(sid)
            assertTrue(items.all { it.status == ImportItemStatus.QUEUED })
            assertNull(items[1].errorDetail)
            val options = optionsOf(sid)
            assertEquals(1, options.committedAt)
            assertEquals(50, options.attemptMarkerAt, "the durable marker advances with the requeue")
            assertEquals(listOf(sid to ImportWorkPolicy.APPEND_OR_REPLACE), scheduler.scheduled)
        }

    @Test
    fun retryOnDoneReopensToFetching() =
        runTest {
            val podcastId = seedPodcast(db, "https://a.example.com/f")
            val sid =
                db.seedImportSession(
                    state = ImportState.DONE,
                    finishedAt = clock.now(),
                    options = ImportOptions(committedAt = 1, attemptMarkerAt = 5),
                    items =
                        listOf(
                            importItem(
                                1,
                                "https://a.example.com/f",
                                status = ImportItemStatus.FETCH_FAILED,
                                podcastId = podcastId,
                            ),
                        ),
                )

            fixUps.retry(sid, setOf(1))

            assertEquals(ImportState.FETCHING, db.importDao().sessionById(sid)?.state)
            assertEquals(
                ImportItemStatus.QUEUED,
                db
                    .importDao()
                    .itemsOf(sid)
                    .single()
                    .status,
            )
            assertEquals(listOf(sid to ImportWorkPolicy.APPEND_OR_REPLACE), scheduler.scheduled)
        }

    @Test
    fun retryLeavesIneligibleAndForeignRowsAlone() =
        runTest {
            val own = seedPodcast(db, "https://own.example.com/f")
            val winner = seedPodcast(db, "https://winner.example.com/f")
            // A merge consumed "consumed.example.com/f": the winner now carries its identity
            // as a MERGE alias and the row was repointed — it is no longer this session's.
            db.podcastDao().insertAlias(
                PodcastUrlAliasEntity(
                    url = "consumed.example.com/f",
                    podcastId = winner,
                    reason = AliasReason.MERGE,
                    addedAt = clock.now(),
                ),
            )
            val sid =
                db.seedImportSession(
                    state = ImportState.FETCHING,
                    items =
                        listOf(
                            importItem(
                                1,
                                "https://own.example.com/f",
                                status = ImportItemStatus.SUBSCRIBED,
                                podcastId = own,
                            ),
                            importItem(
                                2,
                                "https://consumed.example.com/f",
                                status = ImportItemStatus.FETCH_FAILED,
                                podcastId = winner,
                            ),
                        ),
                )

            fixUps.retry(sid, setOf(1, 2))

            assertTrue(podcasts.inner.calls.isEmpty(), "neither row may reach the domain retry")
            assertEquals(ImportItemStatus.SUBSCRIBED, item(sid, 1).status)
            assertEquals(ImportItemStatus.FETCH_FAILED, item(sid, 2).status)
            assertTrue(scheduler.scheduled.isEmpty(), "nothing eligible — nothing scheduled")
        }

    @Test
    fun retryRefusesCancelledAndPreviewSessions() =
        runTest {
            val podcastId = seedPodcast(db, "https://a.example.com/f")
            val cancelled =
                db.seedImportSession(
                    state = ImportState.CANCELLED,
                    items =
                        listOf(
                            importItem(
                                1,
                                "https://a.example.com/f",
                                status = ImportItemStatus.FETCH_FAILED,
                                podcastId = podcastId,
                            ),
                        ),
                )
            val preview =
                db.seedImportSession(
                    items = listOf(importItem(1, "https://b.example.com/f", status = ImportItemStatus.FETCH_FAILED)),
                )

            fixUps.retry(cancelled, setOf(1))
            fixUps.retry(preview, setOf(1))

            assertEquals(ImportState.CANCELLED, db.importDao().sessionById(cancelled)?.state)
            assertEquals(ImportItemStatus.FETCH_FAILED, item(cancelled, 1).status)
            assertTrue(podcasts.inner.calls.isEmpty())
            assertTrue(scheduler.scheduled.isEmpty())
        }

    @Test
    fun aCancelAtTheRequeueCommitStillSchedulesTheHost() =
        runTest {
            val podcastId = seedPodcast(db, "https://a.example.com/f")
            val sid =
                db.seedImportSession(
                    state = ImportState.FETCHING,
                    options = ImportOptions(committedAt = 1, attemptMarkerAt = 5),
                    items =
                        listOf(
                            importItem(
                                1,
                                "https://a.example.com/f",
                                status = ImportItemStatus.FETCH_FAILED,
                                podcastId = podcastId,
                            ),
                        ),
                )

            // Room can commit the requeue transaction and still surface the cancellation —
            // the committed fix-up must keep its APPEND_OR_REPLACE enqueue (05 §8: a request
            // arriving while the worker ends still runs after it).
            lateinit var job: kotlinx.coroutines.Job
            job =
                CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    .launch(start = CoroutineStart.LAZY) { fixUps.retry(sid, setOf(1)) }
            driver.arm("UPDATE import_session SET optionsJson") { job.cancel() }
            job.start()
            job.join()

            assertTrue(job.isCancelled, "Room surfaces the post-commit cancellation")
            assertEquals(1, driver.cancellations, "the commit hook fired")
            assertEquals(ImportItemStatus.QUEUED, item(sid, 1).status, "the requeue committed")
            assertEquals(
                listOf(sid to ImportWorkPolicy.APPEND_OR_REPLACE),
                scheduler.scheduled,
                "the committed requeue is owed its host enqueue",
            )
        }

    @Test
    fun aCorruptOptionsRowKeepsTheFixUpUnapplied() =
        runTest {
            val podcastId = seedPodcast(db, "https://a.example.com/f")
            val sid =
                db.seedImportSession(
                    state = ImportState.FETCHING,
                    items =
                        listOf(
                            importItem(
                                1,
                                "https://a.example.com/f",
                                status = ImportItemStatus.FETCH_FAILED,
                                podcastId = podcastId,
                            ),
                        ),
                )
            db.importDao().setOptionsJson(sid, "{corrupt")

            fixUps.retry(sid, setOf(1))

            assertEquals(ImportItemStatus.FETCH_FAILED, item(sid, 1).status)
            assertTrue(scheduler.scheduled.isEmpty(), "nothing eligible — nothing scheduled")
            assertEquals(
                "{corrupt",
                db.importDao().sessionById(sid)?.optionsJson,
                "defaults never overwrite an unreadable row",
            )
        }

    // --- Edit URL -------------------------------------------------------------------------------------

    @Test
    fun editUrlRoutesThroughTheRepositoryAndMarksSubscribed() =
        runTest {
            val podcastId = seedPodcast(db, "https://dead.example.com/f")
            val sid =
                db.seedImportSession(
                    state = ImportState.DONE,
                    items =
                        listOf(
                            importItem(
                                1,
                                "https://dead.example.com/f",
                                status = ImportItemStatus.GONE,
                                podcastId = podcastId,
                            ),
                        ),
                )

            assertIs<Outcome.Success<*>>(fixUps.editUrl(sid, 1, "https://new.example.com/f"))

            assertEquals(
                listOf("editFeedUrl($podcastId, https://new.example.com/f)"),
                podcasts.inner.calls,
            )
            assertEquals(ImportItemStatus.SUBSCRIBED, item(sid, 1).status)
            // A resolved row never re-queues: the move already fetched the feed.
            assertTrue(scheduler.scheduled.isEmpty())
        }

    @Test
    fun editUrlFailureKeepsTheRowAndReturnsTheError() =
        runTest {
            podcasts.inner.editUrlOutcome = Outcome.Failure(AddPodcastError.NotAFeed)
            val podcastId = seedPodcast(db, "https://dead.example.com/f")
            val sid =
                db.seedImportSession(
                    state = ImportState.DONE,
                    items =
                        listOf(
                            importItem(
                                1,
                                "https://dead.example.com/f",
                                status = ImportItemStatus.GONE,
                                podcastId = podcastId,
                            ),
                        ),
                )

            val outcome = fixUps.editUrl(sid, 1, "https://new.example.com/f")

            assertEquals(AddPodcastError.NotAFeed, assertIs<Outcome.Failure<AddPodcastError>>(outcome).error)
            assertEquals(ImportItemStatus.GONE, item(sid, 1).status)
        }

    // --- Enter password ---------------------------------------------------------------------------------

    @Test
    fun enterPasswordRequeuesOnlyAfterARefusedPair() =
        runTest {
            val podcastId = seedPodcast(db, "https://private.example.com/f")
            val sid =
                db.seedImportSession(
                    state = ImportState.FETCHING,
                    options = ImportOptions(committedAt = 1, attemptMarkerAt = 5),
                    items =
                        listOf(
                            importItem(
                                1,
                                "https://private.example.com/f",
                                status = ImportItemStatus.AUTH_REQUIRED,
                                podcastId = podcastId,
                            ),
                        ),
                )
            val credentials = BasicCredentials("alice", "s3cret")

            podcasts.inner.credentialsOutcome = Outcome.Failure(AddPodcastError.AuthRequired("refused"))
            assertIs<Outcome.Failure<*>>(fixUps.enterPassword(sid, 1, credentials))
            assertEquals(ImportItemStatus.AUTH_REQUIRED, item(sid, 1).status)
            assertTrue(scheduler.scheduled.isEmpty(), "a refused pair stays inline")

            podcasts.inner.credentialsOutcome = Outcome.Success(Unit)
            clock.nowMs = 50
            assertIs<Outcome.Success<*>>(fixUps.enterPassword(sid, 1, credentials))

            assertEquals(
                listOf("setCredentials($podcastId, alice)", "setCredentials($podcastId, alice)"),
                podcasts.inner.calls,
            )
            val item = item(sid, 1)
            assertEquals(ImportItemStatus.QUEUED, item.status)
            assertNull(item.errorDetail)
            assertEquals(50, optionsOf(sid).attemptMarkerAt)
            assertEquals(listOf(sid to ImportWorkPolicy.APPEND_OR_REPLACE), scheduler.scheduled)
        }

    @Test
    fun fixUpsRefuseRowsThatAreNotInAReportStatus() =
        runTest {
            val podcastId = seedPodcast(db, "https://a.example.com/f")
            val sid =
                db.seedImportSession(
                    state = ImportState.FETCHING,
                    items =
                        listOf(
                            importItem(
                                1,
                                "https://a.example.com/f",
                                status = ImportItemStatus.SUBSCRIBED,
                                podcastId = podcastId,
                            ),
                        ),
                )

            assertIs<Outcome.Failure<*>>(
                fixUps.editUrl(sid, 1, "https://new.example.com/f"),
            )
            assertIs<Outcome.Failure<*>>(
                fixUps.enterPassword(sid, 1, BasicCredentials("u", "p")),
            )

            assertTrue(podcasts.inner.calls.isEmpty())
            assertEquals(ImportItemStatus.SUBSCRIBED, item(sid, 1).status)
        }

    // --- Remove ---------------------------------------------------------------------------------------

    @Test
    fun removeUnsubscribesOnlySessionOwnedPodcasts() =
        runTest {
            val owned = seedPodcast(db, "https://owned.example.com/f")
            val existing = seedPodcast(db, "https://existing.example.com/f")
            val winner = seedPodcast(db, "https://winner.example.com/f")
            db.podcastDao().insertAlias(
                PodcastUrlAliasEntity(
                    url = "consumed.example.com/f",
                    podcastId = winner,
                    reason = AliasReason.MERGE,
                    addedAt = clock.now(),
                ),
            )
            val sid =
                db.seedImportSession(
                    state = ImportState.DONE,
                    items =
                        listOf(
                            // The session's own failed import: unsubscribed, then the row goes.
                            importItem(
                                1,
                                "https://owned.example.com/f",
                                status = ImportItemStatus.FETCH_FAILED,
                                podcastId = owned,
                            ),
                            // An existing subscription the file named: never touched.
                            importItem(
                                2,
                                "https://existing.example.com/f",
                                status = ImportItemStatus.ALREADY_SUBSCRIBED,
                                podcastId = existing,
                                selected = false,
                            ),
                            // A merge repointed the row at the winner — foreign work.
                            importItem(
                                3,
                                "https://consumed.example.com/f",
                                status = ImportItemStatus.MERGED,
                                podcastId = winner,
                            ),
                            // A queued row with no podcast: the row alone is deleted.
                            importItem(
                                4,
                                "https://nowhere.example.com/f",
                                status = ImportItemStatus.QUEUED,
                                podcastId = null,
                            ),
                        ),
                )

            fixUps.remove(sid, setOf(1, 2, 3, 4))

            assertEquals(listOf("unsubscribe([$owned])"), podcasts.inner.calls)
            assertNull(db.podcastDao().byId(owned), "the owned subscription is removed")
            assertNotNull(db.podcastDao().byId(existing), "the existing subscription stays")
            assertNotNull(db.podcastDao().byId(winner), "the merge's winner stays")
            // The owned and podcast-less rows are gone; the foreign rows remain as records.
            assertEquals(listOf(2, 3), db.importDao().itemsOf(sid).map { it.ordinal })
        }

    @Test
    fun removeRefusesPreviewAndCancelledSessions() =
        runTest {
            val podcastId = seedPodcast(db, "https://a.example.com/f")
            val preview =
                db.seedImportSession(
                    items =
                        listOf(
                            importItem(
                                1,
                                "https://a.example.com/f",
                                status = ImportItemStatus.QUEUED,
                                podcastId = podcastId,
                            ),
                        ),
                )
            val cancelled =
                db.seedImportSession(
                    state = ImportState.CANCELLED,
                    items =
                        listOf(
                            importItem(
                                1,
                                "https://a.example.com/f",
                                status = ImportItemStatus.FETCH_FAILED,
                                podcastId = podcastId,
                            ),
                        ),
                )

            fixUps.remove(preview, setOf(1))
            fixUps.remove(cancelled, setOf(1))

            assertTrue(podcasts.inner.calls.isEmpty())
            assertNotNull(db.podcastDao().byId(podcastId))
            assertEquals(1, db.importDao().itemsOf(preview).size)
            assertEquals(1, db.importDao().itemsOf(cancelled).size)
        }

    // --- Helpers --------------------------------------------------------------------------------------

    private suspend fun item(
        sessionId: Long,
        ordinal: Int,
    ) = db.importDao().itemsOf(sessionId).single { it.ordinal == ordinal }

    private suspend fun optionsOf(sessionId: Long): ImportOptions =
        ImportOptionsJson.decode(requireNotNull(db.importDao().sessionById(sessionId)).optionsJson!!)

    /**
     * Records calls through [FakePodcastRepository] and runs the real `deleteCascade` for
     * `unsubscribe` — the `import_item` `SET NULL` the remove path depends on.
     */
    private inner class CascadingPodcasts(
        val inner: FakePodcastRepository = FakePodcastRepository(),
    ) : PodcastRepository by inner {
        override suspend fun unsubscribe(
            podcastIds: List<Long>,
            origin: ChangeOrigin,
        ): List<Long> {
            val removed = podcastIds.filter { db.podcastDao().byId(it) != null }
            for (id in removed) db.podcastDao().deleteCascade(id, clock.now())
            inner.unsubscribe(podcastIds, origin)
            return removed
        }
    }
}
