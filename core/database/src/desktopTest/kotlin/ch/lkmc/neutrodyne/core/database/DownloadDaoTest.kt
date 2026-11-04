// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import app.cash.turbine.test
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ch.lkmc.neutrodyne.core.model.DownloadError as Error
import ch.lkmc.neutrodyne.core.model.DownloadLane as Lane
import ch.lkmc.neutrodyne.core.model.DownloadState as State
import ch.lkmc.neutrodyne.core.model.WaitReason as Wait

class DownloadDaoTest {
    private val db = TestDb.inMemory()
    private val dao = db.downloadDao()

    @After fun close() = db.close()

    @Test fun entryColoursFollowResolvedArtworkWithoutChangingDownloadRows() =
        runTest {
            val id = seed(1).single()
            dao.insert(row(id))
            dao.observeEntries().test {
                val initial = awaitItem().single()
                assertNull(initial.artworkAvgArgb)
                val key = initial.artworkKey
                db.artworkDao().upsert(ArtworkEntity(key = key, version = 4, avgArgb = 0xff234567.toInt()))
                val coloured = awaitItem().single()
                assertEquals(0xff234567.toInt(), coloured.artworkAvgArgb)
                assertEquals(4, coloured.artworkVersion)
                assertEquals(initial.download, coloured.download)
                assertEquals(initial.sortDate, coloured.sortDate)
                db.artworkDao().upsert(ArtworkEntity(key = key, version = 4, avgArgb = 0))
                assertEquals(0, awaitItem().single().artworkAvgArgb)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test fun externalChannelsCannotBeClaimedEvenWithRssTaggedDownloads() =
        runTest {
            val channel =
                db.podcastDao().insertPodcast(
                    podcastEntity(id = 0, feedKey = "channel").copy(
                        sourceType = ch.lkmc.neutrodyne.core.model.SourceType.YOUTUBE_CHANNEL,
                        youtubeChannelId = "UCBR8-60-B28hp2BmDPdntcQ",
                    ),
                )
            val episode =
                db.episodeDao().insertStub(
                    episodeEntity(id = 0, podcastId = channel, identityKey = "external"),
                )
            dao.insert(row(episode).copy(sourceKind = SourceKind.RSS_ENCLOSURE))
            assertEquals(0, dao.claim(episode, "direct"))
            assertEquals(0, dao.queuedNeeds(Lane.MANUAL, 100).queued)
            assertTrue(dao.candidates(listOf(Lane.MANUAL), 100, true, true, false, 0).isEmpty())
            assertNull(dao.claimNext(listOf(Lane.MANUAL), 100, true, true, false, "runner") { true })
            assertNull(dao.byEpisode(episode)?.runnerToken)
        }

    @Test fun stateAndOwnershipGuards() =
        runTest {
            val ids = seed()
            State.entries.forEachIndexed { i, state ->
                val row = row(ids[i]).copy(state = state)
                dao.insert(row)
                assertEquals(0, dao.updateUnclaimed(row, state), "no-op $state")
                assertEquals(0, dao.updateUnclaimed(row.copy(priority = 200), State.entries[(i + 1) % ids.size]))
                assertEquals(1, dao.updateUnclaimed(row.copy(priority = 200), state))
                assertEquals(if (state == State.QUEUED) 1 else 0, dao.claim(row.episodeId, "owner"), "$state")
                assertEquals(0, dao.claim(row.episodeId, "duplicate"))
            }
            val owned = assertNotNull(dao.byEpisode(ids[0]))
            assertEquals(Wait.NONE, owned.waitReason)
            assertEquals(0, dao.updateUnclaimed(owned.copy(priority = 300), owned.state))
            assertEquals(0, dao.removeUnclaimed(listOf(ids[0])))
            assertEquals(0, dao.removeOwned(ids[0], "stale"))
            assertEquals(1, dao.removeOwned(ids[0], "owner"))
            assertEquals(0, dao.removeOwned(ids[0], "owner"))
        }

    @Test fun concurrentTransactionalClaimsHaveOneOwner() =
        runTest {
            val id = seed(1).single()
            dao.insert(row(id))
            val claims =
                coroutineScope {
                    (1..8)
                        .map { n ->
                            async(Dispatchers.Default) {
                                dao.claimNext(listOf(Lane.MANUAL), 100, true, true, true, "r$n") { true }
                            }
                        }.awaitAll()
                }.filterNotNull()
            assertEquals(1, claims.size)
            assertEquals(State.RESOLVING, claims.single().state)
            assertEquals(0, dao.claim(id, ""))
        }

    @Test fun orderedCandidatePagesAndConditions() =
        runTest {
            val ids = seed(30)
            ids.forEachIndexed { i, id ->
                dao.insert(
                    row(id).copy(
                        priority =
                            if (i ==
                                25
                            ) {
                                200
                            } else {
                                100
                            },
                        requestedAt = 10,
                    ),
                )
            }

            suspend fun page(offset: Int) =
                dao.candidates(listOf(Lane.MANUAL), 100, true, true, true, offset).map { it.episodeId }
            assertEquals(listOf(ids[25]) + ids.take(19), page(0))
            assertEquals(ids.filter { it != ids[25] }.drop(19), page(20))
            assertEquals(
                ids.last(),
                dao
                    .claimNext(listOf(Lane.MANUAL), 100, true, true, true, "page", 20) {
                        it.episodeId ==
                            ids.last()
                    }?.episodeId,
            )
            assertNull(dao.claimNext(listOf(Lane.MANUAL), 100, true, true, true, "none") { false })
            val blocked =
                listOf(
                    row(ids[0]).copy(allowMetered = false),
                    row(ids[1]).copy(requireCharging = true),
                    row(ids[2]).copy(nextAttemptAt = 101),
                    row(ids[3]).copy(sourceKind = SourceKind.YOUTUBE),
                    row(ids[4]).copy(lane = Lane.AUTO),
                    row(ids[5]).copy(runnerToken = "stale"),
                )
            blocked.forEach {
                dao.removeUnclaimed(listOf(it.episodeId))
                dao.insert(it)
            }
            val eligible = dao.candidates(listOf(Lane.MANUAL), 100, false, false, false).map { it.episodeId }
            assertTrue(eligible.none { it in ids.take(6) })
            assertEquals(listOf(ids[4]), dao.candidates(listOf(Lane.AUTO), 100, true, true, true).map { it.episodeId })
        }

    @Test fun finishGuardsPreserveOriginalSourceAndCurrentPolicy() =
        runTest {
            val id = seed(1).single()
            val original = row(id).copy(sourceRef = "https://origin/private.mp3", lane = Lane.AUTO)
            dao.insert(original)
            dao.claim(id, "owner")
            val bytes =
                original.copy(
                    state = State.DOWNLOADING,
                    etag = "\"etag\"",
                    lastModified = "then",
                    tempPath = "part",
                    downloadedBytes = 50,
                    totalBytes = 100,
                    sourceRef = "https://cdn/token",
                    mimeType = "audio/mpeg",
                )
            dao.updatePolicy(id, Lane.MANUAL, 200, true, false)
            assertEquals(0, dao.transition(bytes, State.RESOLVING, "stale"))
            assertEquals(0, dao.transition(bytes, State.QUEUED, "owner"))
            assertEquals(1, dao.transition(bytes, State.RESOLVING, "owner"))
            var stored = assertNotNull(dao.byEpisode(id))
            assertEquals(
                listOf(bytes.etag, bytes.lastModified, bytes.tempPath),
                listOf(stored.etag, stored.lastModified, stored.tempPath),
            )
            assertEquals(50L, stored.downloadedBytes)
            assertEquals("owner", stored.runnerToken)
            assertEquals(original.sourceRef, stored.sourceRef)
            assertEquals(Lane.MANUAL, stored.lane)
            assertEquals(200, stored.priority)
            assertTrue(stored.allowMetered)
            val completed =
                bytes.copy(
                    state = State.COMPLETED,
                    finalUri = "file:///one",
                    completedAt = 42,
                    downloadedBytes = 100,
                )
            assertEquals(1, dao.transition(completed, State.DOWNLOADING, "owner"))
            stored = assertNotNull(dao.byEpisode(id))
            assertNull(stored.runnerToken)
            assertEquals(listOf(CompletedDownloadRow(id, "file:///one")), dao.completedIndex())
            assertEquals(0, dao.transition(bytes, State.COMPLETED, "owner"))
        }

    @Test fun queueAggregatesAndWaitWrites() =
        runTest {
            val ids = seed()
            assertEquals(
                QueuedDownloadNeeds(0, null, null, null, null, null, null, null, null),
                dao.queuedNeeds(Lane.MANUAL, 100),
            )
            val rows =
                listOf(
                    row(
                        ids[0],
                    ).copy(allowMetered = false, requireCharging = true, totalBytes = 50, downloadedBytes = 100),
                    row(ids[1]).copy(nextAttemptAt = 200, estimatedBytes = 80, downloadedBytes = 10),
                    row(ids[2]).copy(allowMetered = false, requireCharging = true, nextAttemptAt = 150),
                    row(ids[3]).copy(state = State.PAUSED),
                    row(ids[4]).copy(runnerToken = "owner"),
                )
            rows.forEach { dao.insert(it) }
            assertEquals(
                QueuedDownloadNeeds(3, 1, true, false, false, 150, 200, 200, 157286470),
                dao.queuedNeeds(Lane.MANUAL, 100),
            )
            assertEquals(3, dao.markWait(ids, Wait.STORAGE, null))
            assertEquals(0, dao.markWait(ids, Wait.STORAGE, null))
            assertEquals(3, dao.clearStorageWaits())
            assertEquals(0, dao.clearStorageWaits())
        }

    @Test fun orphanResetKeepsLiveOwnersAndResumeMetadata() =
        runTest {
            val ids = seed()
            listOf(State.RESOLVING, State.DOWNLOADING, State.VERIFYING).forEachIndexed { i, state ->
                dao.insert(
                    row(ids[i]).copy(
                        state = state,
                        runnerToken =
                            if (i ==
                                0
                            ) {
                                "live"
                            } else {
                                "dead"
                            },
                        downloadedBytes = 50,
                        etag = "etag",
                        tempPath = "part",
                    ),
                )
            }
            assertEquals(2, dao.resetOrphanedRunners(setOf("live"), -1))
            assertEquals(0, dao.resetOrphanedRunners(setOf("live"), -1))
            assertEquals("live", dao.byEpisode(ids[0])?.runnerToken)
            val reset = assertNotNull(dao.byEpisode(ids[1]))
            assertEquals(listOf("part", "etag"), listOf(reset.tempPath, reset.etag))
            assertEquals(50L, reset.downloadedBytes)
            assertEquals(State.QUEUED, reset.state)
            assertEquals(Wait.SYSTEM, reset.waitReason)
            assertEquals(1, dao.resetOrphanedRunners(emptySet(), -1))
        }

    @Test fun changedEnclosuresReturnOldPathsAndResetValidators() =
        runTest {
            val ids = seed()
            val errors =
                listOf(Error.HTTP_NOT_FOUND, Error.HTTP_GONE, Error.HTTP_CLIENT, Error.NOT_MEDIA, Error.HTTP_AUTH)
            errors.forEachIndexed { i, error ->
                dao.insert(
                    row(ids[i]).copy(
                        state = State.FAILED,
                        sourceRef = "old",
                        lastError = error,
                        tempPath = "part",
                        downloadedBytes = 50,
                        etag = "etag",
                        lastModified = "then",
                        attempt = 4,
                    ),
                )
            }
            assertEquals(ids.take(4), dao.requeueChangedEnclosures(100).map { it.episodeId })
            assertTrue(dao.requeueChangedEnclosures(100).isEmpty())
            val reset = assertNotNull(dao.byEpisode(ids[0]))
            assertEquals("https://origin/${ids[0]}.mp3", reset.sourceRef)
            assertEquals(listOf(null, null, null), listOf(reset.tempPath, reset.etag, reset.lastModified))
            assertEquals(0L, reset.downloadedBytes)
            assertEquals(0, reset.attempt)
        }

    @Test fun countAndEntryFlowsReflectTransitionsWithoutPositionInvalidation() =
        runTest {
            val ids = seed()
            dao.observeFailedCount().distinctUntilChanged().test {
                assertEquals(0, awaitItem())
                ids.take(2).forEachIndexed {
                    i,
                    id,
                    ->
                    dao.insert(row(id).copy(state = State.FAILED))
                    assertEquals(i + 1, awaitItem())
                }
                dao.removeUnclaimed(listOf(ids[0]))
                assertEquals(1, awaitItem())
                dao.updateUnclaimed(row(ids[1]), State.FAILED)
                assertEquals(0, awaitItem())
            }
            dao.observeEntries().test {
                assertEquals(1, awaitItem().size)
                db.positionDao().upsert(
                    EpisodePositionEntity(ids[1], 1000, positionSource = PositionSource.STREAM, updatedAt = 1),
                )
                db.episodeStateDao().upsert(episodeStateEntity(ids[1]) { copy(isFavorite = true, playedAt = 1) })
                val entry = awaitItem().single()
                assertTrue(entry.isFavorite)
                assertEquals(1L, entry.playedAt)
                assertEquals("Example podcast", entry.podcastTitle)
                expectNoEvents()
            }
        }

    @Test fun sourceBatchesPreserveAlternatesEffectiveDurationAndSession() =
        runTest {
            val ids = seed(505)
            db.episodeStateDao().upsert(episodeStateEntity(ids[0]) { copy(measuredDurationMs = 1234) })
            db.useWriterConnection {
                it.exec(
                    "INSERT INTO episode_alt_enclosure(episodeId, ordinal, type, sourcesJson)" +
                        " VALUES (${ids[0]}, 2, 'audio/mpeg', '[{\"uri\":\"https://alt/audio.mp3\"}]')",
                )
            }
            val sources = db.episodeDao().downloadSources(ids + ids[0])
            assertEquals(505, sources.size)
            val first = sources.single { it.episodeId == ids[0] }
            assertEquals(1234L, first.durationMs)
            assertEquals(2, first.audioAlternates.single().ordinal)
            assertNull(db.playSessionDao().observeCurrentEpisodeId().first())
            assertTrue(db.episodeDao().downloadSources(emptyList()).isEmpty())
            assertTrue(db.episodeDao().downloadSources(listOf(-1)).isEmpty())
        }

    @Test fun automationQueriesRetainProtectedWindowAndMoveOnlyCompleted() =
        runTest {
            val ids = seed(4)
            ids.forEachIndexed { i, id ->
                dao.insert(
                    row(id).copy(
                        state = State.COMPLETED,
                        lane = if (i == 3) Lane.MANUAL else Lane.AUTO,
                        rootId = "old",
                    ),
                )
            }
            db.episodeStateDao().upsert(episodeStateEntity(ids[0]) { copy(playedAt = 10) })
            db.episodeStateDao().upsert(episodeStateEntity(ids[1]) { copy(isFavorite = true) })
            db.episodeStateDao().upsert(episodeStateEntity(ids[2]) { copy(startedAt = 1) })
            assertEquals(listOf(ids[0]), dao.cleanupCandidates(3).map { it.episodeId })
            val podcast = requireNotNull(db.episodeDao().downloadSources(ids).firstOrNull()).podcastId
            val rolling = dao.unplayedAutoCompleted(listOf(podcast), 3)
            assertEquals(setOf(ids[1], ids[2]), rolling.map { it.episodeId }.toSet())
            assertTrue(rolling.all { it.protected })
            assertEquals(ids.take(2), dao.rowsOnOtherRoots("new", 2).map { it.episodeId })
            assertTrue(dao.rowsOnOtherRoots("old", 20).isEmpty())
            assertEquals(listOf(ids[0]), dao.observePlayedCompletedIds().first())
        }

    @Test fun taskManagerResetLeavesLiveAndOtherLaneOwnersForGenericReset() =
        runTest {
            val ids = seed(5)
            dao.insert(row(ids[0]).copy(state = State.DOWNLOADING, runnerToken = "uidt:stale"))
            dao.insert(row(ids[1]))
            dao.insert(row(ids[2]).copy(state = State.DOWNLOADING, runnerToken = "uidt:live"))
            dao.insert(row(ids[3]).copy(state = State.DOWNLOADING, lane = Lane.AUTO, runnerToken = "wm:stale"))
            dao.insert(row(ids[4]).copy(state = State.PAUSED))
            assertEquals(2, dao.resetTaskManagerStoppedRunners(setOf("uidt:live"), 99))
            assertEquals(1, dao.resetOrphanedRunners(setOf("uidt:live"), 100))
            assertEquals(State.PAUSED, dao.byEpisode(ids[0])?.state)
            assertEquals(99, dao.byEpisode(ids[1])?.lastStopReason)
            assertEquals(State.DOWNLOADING, dao.byEpisode(ids[2])?.state)
            assertEquals(Wait.SYSTEM, dao.byEpisode(ids[3])?.waitReason)
            assertEquals(State.PAUSED, dao.byEpisode(ids[4])?.state)
        }

    @Test fun taskManagerResetFiltersOwnersAndPreservesTransferProgress() =
        runTest {
            val ids = seed(11)
            val original =
                row(ids[0]).copy(
                    state = State.VERIFYING,
                    runnerToken = "uidt:1000:stopped",
                    downloadedBytes = 2048,
                    tempPath = ".partial/transfer.part",
                    etag = "strong-etag",
                    attempt = 2,
                    nextAttemptAt = 5000,
                )
            dao.insert(original)
            dao.insert(row(ids[1]).copy(waitReason = Wait.SYSTEM, nextAttemptAt = 9000))
            dao.insert(row(ids[2]).copy(state = State.RESOLVING, runnerToken = "work:manual:stale"))
            dao.insert(row(ids[3]).copy(state = State.DOWNLOADING, lane = Lane.AUTO, runnerToken = "uidt:1000"))
            dao.insert(row(ids[4]).copy(state = State.DOWNLOADING, runnerToken = "uidt:2000:live"))
            dao.insert(row(ids[5]).copy(state = State.RESOLVING, runnerToken = null))
            dao.insert(row(ids[6]).copy(lane = Lane.AUTO))
            dao.insert(row(ids[7]).copy(state = State.PAUSED))
            dao.insert(row(ids[8]).copy(state = State.COMPLETED))
            dao.insert(row(ids[9]).copy(state = State.QUEUED, runnerToken = "uidt:2000:live"))
            dao.insert(row(ids[10]).copy(state = State.FAILED))
            val before = dao.allRows().associateBy { it.episodeId }
            val live = setOf("uidt:2000:live")

            assertEquals(2, dao.resetTaskManagerStoppedRunners(live, -4))
            assertEquals(0, dao.resetTaskManagerStoppedRunners(live, -4))
            assertEquals(
                original.copy(
                    state = State.PAUSED,
                    runnerToken = null,
                    waitReason = Wait.NONE,
                    lastError = Error.CANCELLED_BY_SYSTEM,
                    lastStopReason = -4,
                ),
                dao.byEpisode(ids[0]),
            )
            assertEquals(
                before.getValue(ids[1]).copy(state = State.PAUSED, waitReason = Wait.NONE, lastStopReason = -4),
                dao.byEpisode(ids[1]),
            )
            // The dedicated transaction must not consume rows owned by other runners or final states.
            for (id in ids.drop(2)) assertEquals(before.getValue(id), dao.byEpisode(id))

            assertEquals(3, dao.resetOrphanedRunners(live, -1))
            for (id in ids.slice(2..3) + ids[5]) {
                assertEquals(State.QUEUED, dao.byEpisode(id)?.state)
                assertEquals(Wait.SYSTEM, dao.byEpisode(id)?.waitReason)
                assertEquals(-1, dao.byEpisode(id)?.lastStopReason)
            }
            assertEquals(State.PAUSED, dao.byEpisode(ids[0])?.state)
            assertEquals(-4, dao.byEpisode(ids[1])?.lastStopReason)
        }

    @Test fun taskManagerResetRollsBackActivePauseIfQueuePauseFails() =
        runTest {
            val ids = seed(2)
            dao.insert(row(ids[0]).copy(state = State.DOWNLOADING, runnerToken = "uidt:1000"))
            dao.insert(row(ids[1]))
            val before = dao.allRows()
            // Fail the companion statement to prove both updates share one write transaction.
            db.useWriterConnection { connection ->
                connection.exec(
                    "CREATE TEMP TRIGGER reject_queue_pause BEFORE UPDATE ON download" +
                        " WHEN OLD.state = 'QUEUED' AND NEW.state = 'PAUSED'" +
                        " BEGIN SELECT RAISE(ABORT, 'Queue pause rejected'); END",
                )
            }
            assertFailsWith<androidx.sqlite.SQLiteException> {
                dao.resetTaskManagerStoppedRunners(emptySet(), -4)
            }
            assertEquals(before, dao.allRows())
            db.useWriterConnection { it.exec("DROP TRIGGER reject_queue_pause") }
            assertEquals(2, dao.resetTaskManagerStoppedRunners(emptySet(), -4))
        }

    @Test fun autoCandidatesHonorWatermarkTombstoneAndExistingSlots() =
        runTest {
            val ids = seed(4)
            db.useWriterConnection { connection ->
                ids.forEachIndexed { i, id ->
                    connection.exec("UPDATE episode SET isNew=1, firstSeenAt=${i + 1}, sortDate=${i + 1} WHERE id=$id")
                }
            }
            val podcast =
                db
                    .episodeDao()
                    .downloadSources(ids)
                    .first()
                    .podcastId
            dao.insert(row(ids[3]))
            db.episodeStateDao().upsert(episodeStateEntity(ids[2]) { copy(downloadDismissedAt = 1) })
            assertEquals(listOf(ids[3], ids[1]), dao.autoCandidates(podcast, 1, 3, false, false).map { it.id })
            assertEquals(State.QUEUED, dao.autoCandidates(podcast, 1, 1, false, false).single().downloadState)
            assertTrue(dao.autoCandidates(podcast, 4, 3, false, false).isEmpty())
        }

    private fun row(id: Long) =
        ch.lkmc.neutrodyne.core.testing.database
            .downloadEntity(id, State.QUEUED)
            .copy(allowMetered = true)

    private suspend fun seed(count: Int = State.entries.size): List<Long> =
        db.withWriteTransaction {
            val podcast = db.podcastDao().insertPodcast(podcastEntity())
            (1..count).map { n ->
                db.episodeDao().insertStub(
                    episodeEntity(podcastId = podcast) { copy(enclosureUrl = "https://origin/$n.mp3") },
                )
            }
        }
}
