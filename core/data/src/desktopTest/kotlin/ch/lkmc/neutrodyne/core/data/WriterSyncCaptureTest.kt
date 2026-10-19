// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.data.playback.PositionWriter
import ch.lkmc.neutrodyne.core.data.repo.EpisodeRepositoryImpl
import ch.lkmc.neutrodyne.core.data.repo.PodcastMerger
import ch.lkmc.neutrodyne.core.database.EpisodePositionEntity
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.PodcastUrlAliasEntity
import ch.lkmc.neutrodyne.core.database.QueueEntryEntity
import ch.lkmc.neutrodyne.core.database.SyncClockEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.model.AliasReason
import ch.lkmc.neutrodyne.core.model.ChangeOrigin
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupShowNotesSanitizer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real writer transactions over installed triggers, including actions hidden by projections. */
class WriterSyncCaptureTest {
    private val clock = TestClock(1_800_000_000_000)
    private val db = TestDb.inMemory(clock = clock)
    private val writer = PositionWriter(db)
    private val episodes = EpisodeRepositoryImpl(db, JsoupShowNotesSanitizer(), clock)

    @AfterTest fun close() = db.close()

    @Test fun unplayedCapturesFalseAndResetWithAbsentOrAlreadyDefaultRows() =
        runTest {
            val id = seed()
            link()
            // MS2 owns interpreting raw; this opaque row models raw played/progress behind defaults.
            db.syncClockDao().upsert(SyncClockEntity("episode", rid(id), "{\"raw\":{\"played\":true,\"pos\":900}}"))
            repeat(2) { iteration ->
                if (iteration == 1) {
                    db.episodeStateDao().upsert(EpisodeStateEntity(id, updatedAt = 1))
                    db.positionDao().upsert(
                        EpisodePositionEntity(id, 0, positionSource = PositionSource.STREAM, updatedAt = 1),
                    )
                }
                val stamp = db.syncStateDao().get()!!.hlc
                episodes.setPlayed(listOf(id, id, Long.MAX_VALUE), false)
                assertEquals(
                    setOf("played", "pos"),
                    db
                        .syncOutboxDao()
                        .rows()
                        .map { it.field }
                        .toSet(),
                )
                assertEquals("{\"played\":false,\"playedAt\":null}", field(id, "played"))
                assertEquals("{\"ms\":0,\"reset\":true}", field(id, "pos"))
                assertTrue(db.syncStateDao().get()!!.hlc > stamp)
                assertNull(db.episodeStateDao().byEpisode(id)?.playedAt)
            }
        }

    @Test fun positionOnlyResetCapturesIntentWithoutPlayedAction() =
        runTest {
            val id = seed()
            writer.markPlayed(id, clock.now())
            link()
            repeat(2) { writer.reset(id, clock.now()) }
            assertEquals(listOf("pos"), db.syncOutboxDao().rows().map { it.field })
            assertEquals("{\"ms\":0,\"reset\":true}", field(id, "pos"))
            assertEquals(clock.now(), db.episodeStateDao().byEpisode(id)?.playedAt)
        }

    @Test fun replayUnplayedPreservesResumeAndCapturesOnlyPlayedEvenAtDefault() =
        runTest {
            val id = seed()
            writer.save(id, 400, 1000, PositionSource.STREAM, clock.now(), 1) { true }
            link()
            repeat(2) { writer.markUnplayedForReplay(id, clock.now()) }
            assertEquals(listOf("played"), db.syncOutboxDao().rows().map { it.field })
            assertEquals("{\"played\":false,\"playedAt\":null}", field(id, "played"))
            assertEquals(400, db.positionDao().byEpisode(id)?.positionMs)
        }

    @Test fun savesCoalesceLiteralPositionsAndIdenticalSaveDoesNotTick() =
        runTest {
            val id = seed()
            link()
            for (pos in listOf(500L, 100L, 700L)) {
                assertTrue(writer.save(id, pos, 1000, PositionSource.STREAM, clock.now(), 1) { true })
            }
            val before = db.syncOutboxDao().rows()
            assertEquals(1, before.size)
            assertEquals("{\"ms\":700,\"dur\":1000,\"src\":\"STREAM\"}", field(id, "pos"))
            writer.save(id, 700, 1000, PositionSource.STREAM, clock.now() + 5000, 1) { true }
            assertEquals(before, db.syncOutboxDao().rows())
        }

    @Test fun bulkFiveThousandUsesActualPlayedChainAndRollsBackCaptureOnFailure() =
        runTest {
            val podcast = db.podcastDao().insertPodcast(podcastEntity())
            val ids =
                db.withWriteTransaction {
                    (1..5000).map { db.episodeDao().insertStub(episodeEntity(podcastId = podcast)) }
                }
            val first = ids.first()
            db.positionDao().upsert(
                EpisodePositionEntity(first, 80, positionSource = PositionSource.STREAM, updatedAt = 1),
            )
            db.queueDao().insert(QueueEntryEntity(episodeId = first, orderKey = "a0", addedAt = 1))
            link()
            db.useWriterConnection {
                it.execSql(
                    "CREATE TRIGGER reject_remove BEFORE DELETE ON queue_entry" +
                        " BEGIN SELECT RAISE(ABORT, 'test'); END",
                )
            }
            assertFailsWith<Exception> { episodes.setPlayed(ids, true) }
            assertTrue(db.syncOutboxDao().rows().isEmpty())
            assertEquals(0, db.syncStateDao().get()?.hlc)
            assertNull(db.episodeStateDao().byEpisode(first))
            assertEquals(80, db.positionDao().byEpisode(first)?.positionMs)
            db.useWriterConnection { it.execSql("DROP TRIGGER reject_remove") }
            episodes.setPlayed(ids, true)
            val rows = db.syncOutboxDao().rows()
            assertEquals(5000, rows.count { it.field == "played" })
            assertEquals(5000, rows.count { it.field == "playCount" })
            assertEquals("{\"ms\":0,\"reset\":true}", field(first, "pos"))
            assertTrue(db.queueDao().entries().isEmpty())
            episodes.setPlayed(ids, true)
            assertEquals(rows, db.syncOutboxDao().rows())
        }

    @Test fun localMergeCapturesFrozenMoveAndAllAliasesInsteadOfUnsubscribeOrDerivedReset() =
        runTest {
            val loser = db.podcastDao().insertPodcast(podcastEntity(feedUrl = "https://old.test/feed"))
            val winner = db.podcastDao().insertPodcast(podcastEntity(feedUrl = "https://new.test/feed"))
            val losingEpisode = db.episodeDao().insertStub(episodeEntity(podcastId = loser, identityKey = "same"))
            val winningEpisode = db.episodeDao().insertStub(episodeEntity(podcastId = winner, identityKey = "same"))
            db.episodeStateDao().upsert(EpisodeStateEntity(losingEpisode, playedAt = 200, updatedAt = 200))
            db.positionDao().upsert(
                EpisodePositionEntity(winningEpisode, 99, positionSource = PositionSource.STREAM, updatedAt = 100),
            )
            db.podcastDao().insertAlias(PodcastUrlAliasEntity("old.test/alias\"", loser, AliasReason.REDIRECT, 1))
            db.podcastDao().insertAlias(PodcastUrlAliasEntity("new.test/alias", winner, AliasReason.REDIRECT, 1))
            val loserRow = db.podcastDao().byId(loser)!!
            val winnerRow = db.podcastDao().byId(winner)!!
            link()
            PodcastMerger(db, FakeCredentialCommitCoordinator(), clock).merge(loser, winner, ChangeOrigin.LOCAL)
            val rows = db.syncOutboxDao().rows()
            assertNull(db.podcastDao().byId(loser))
            assertTrue(rows.none { it.field == "subscribed" || it.field == "pos" })
            assertEquals(
                Json.encodeToString(winnerRow.feedUrl),
                rows.single { it.rid == loserRow.syncId && it.field == "feedUrl" }.value,
            )
            val keys =
                Json.parseToJsonElement(
                    rows.single { it.rid == loserRow.syncId && it.field == "feedKeys" }.value!!,
                )
            assertEquals(
                setOf(loserRow.feedKey, winnerRow.feedKey, "old.test/alias\"", "new.test/alias"),
                keys.jsonArray.map { it.jsonPrimitive.content }.toSet(),
            )
            assertEquals(0, db.positionDao().byEpisode(winningEpisode)?.positionMs)
            assertEquals(200, db.episodeStateDao().byEpisode(winningEpisode)?.playedAt)
        }

    private suspend fun seed(): Long {
        val podcast = db.podcastDao().insertPodcast(podcastEntity())
        return db.episodeDao().insertStub(episodeEntity(podcastId = podcast))
    }

    @Test fun explicitIntentsRollBackWithTheirTransaction() =
        runTest {
            val id = seed()
            link()
            assertFailsWith<IllegalStateException> {
                db.withWriteTransaction {
                    writer.reset(id, clock.now())
                    episodes.setPlayed(listOf(id), false)
                    error("abort intent")
                }
            }
            assertTrue(db.syncOutboxDao().rows().isEmpty())
            assertEquals(0, db.syncStateDao().get()?.hlc)
        }

    @Test fun actualSavesAndMarksAreInertWhenDisabledOrApplying() =
        runTest {
            val id = seed()

            suspend fun write() {
                writer.save(id, 200, 1000, PositionSource.STREAM, clock.now(), clock.now() + 1) { true }
                writer.markPlayed(id, clock.now())
                writer.markUnplayedForReplay(id, clock.now())
                writer.reset(id, clock.now())
                episodes.setPlayed(listOf(id), false)
            }
            write()
            assertTrue(db.syncOutboxDao().rows().isEmpty())
            assertEquals(0, db.syncStateDao().get()?.hlc)
            link()
            db.withWriteTransaction { db.syncStateDao().withApplying { write() } }
            assertTrue(db.syncOutboxDao().rows().isEmpty())
            assertEquals(0, db.syncStateDao().get()?.hlc)
        }

    @Test fun remoteAndDisabledMergesHaveNoCaptureAndFailedLocalMergeRollsBack() =
        runTest {
            suspend fun pair(): Pair<Long, Long> =
                db.podcastDao().insertPodcast(podcastEntity()) to db.podcastDao().insertPodcast(podcastEntity())
            val merger = PodcastMerger(db, FakeCredentialCommitCoordinator(), clock)
            val disabled = pair()
            merger.merge(disabled.first, disabled.second, ChangeOrigin.LOCAL)
            assertTrue(db.syncOutboxDao().rows().isEmpty())
            val remote = pair()
            val local = pair()
            link()
            merger.merge(remote.first, remote.second, ChangeOrigin.SYNC)
            assertTrue(db.syncOutboxDao().rows().isEmpty())
            db.useWriterConnection {
                it.execSql("CREATE TRIGGER reject_merge BEFORE DELETE ON podcast BEGIN SELECT RAISE(ABORT, 'x'); END")
            }
            assertFailsWith<Exception> { merger.merge(local.first, local.second, ChangeOrigin.LOCAL) }
            assertTrue(db.syncOutboxDao().rows().isEmpty())
            assertEquals(0, db.syncStateDao().get()?.hlc)
            assertTrue(db.podcastDao().byId(local.first) != null)
            assertEquals(false, db.syncStateDao().get()?.applying)
        }

    private suspend fun link() =
        db.withWriteTransaction {
            db.syncStateDao().upsert(SyncStateEntity(enabled = true, nodeId = "0000000000000001"))
        }

    private suspend fun rid(id: Long): String {
        val episode = db.episodeDao().byId(id)!!
        return db.podcastDao().byId(episode.podcastId)!!.syncId + episode.identityKey
    }

    private suspend fun field(
        id: Long,
        name: String,
    ): String? = db.syncOutboxDao().get("episode", rid(id), name)?.value
}
