// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.repo.GroupRepositoryImpl
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastGroupEntity
import ch.lkmc.neutrodyne.core.database.QueueEntryEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.OrderKeys
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSnapshotScheduler
import ch.lkmc.neutrodyne.core.testing.ProtocolOrderKeys
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Minimal-write group reordering (05 Order): a single drag writes exactly one `orderKey`; only an
 * equal-bound collision or a key past [OrderKeys.MAX_LENGTH] rewrites the whole list. Writes are
 * counted by diffing rows before and after the reorder.
 */
class GroupOrderTest {
    private val clock = TestClock()
    private val db = TestDb.inMemory(clock = clock)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repo =
        GroupRepositoryImpl(
            db,
            clock,
            TestOrderKeys,
            FakeGroupChannelSync(),
            FakeRefreshController(),
            FakeSnapshotScheduler(),
            appScope,
        )

    @AfterTest fun close() {
        // Undo-expiry jobs must not outlive the database they write to.
        appScope.cancel()
        db.close()
    }

    /** `TestOrderKeys` appends 0.5, 0.75, 0.875, 0.9375. */
    private suspend fun groups(count: Int): List<Long> =
        (1..count).map { assertIs<Outcome.Success<Long>>(repo.create(GroupDraft("G$it"))).value }

    private suspend fun rows(): List<PodcastGroupEntity> = db.groupDao().groupsInOrder()

    private fun changedKeys(
        before: List<PodcastGroupEntity>,
        after: List<PodcastGroupEntity>,
    ): Set<Long> =
        after
            .mapNotNull { row ->
                val old = before.single { it.id == row.id }
                if (old.orderKey != row.orderKey) row.id else null
            }.toSet()

    @Test fun movingTheLastGroupToTheFrontWritesOneKey() =
        runTest {
            val (g1, g2, g3, g4) = groups(4).let { listOf(it[0], it[1], it[2], it[3]) }
            val before = rows()
            assertEquals(Outcome.Success(Unit), repo.reorder(listOf(g4, g1, g2, g3)))
            val after = rows()
            assertEquals(setOf(g4), changedKeys(before, after))
            assertEquals(listOf(g4, g1, g2, g3), after.map { it.id })
            assertTrue(after.first().orderKey < before.first().orderKey)
        }

    @Test fun movingTheFirstGroupToTheEndWritesOneKey() =
        runTest {
            val (g1, g2, g3, g4) = groups(4).let { listOf(it[0], it[1], it[2], it[3]) }
            val before = rows()
            assertEquals(Outcome.Success(Unit), repo.reorder(listOf(g2, g3, g4, g1)))
            val after = rows()
            assertEquals(setOf(g1), changedKeys(before, after))
            assertEquals(listOf(g2, g3, g4, g1), after.map { it.id })
            assertTrue(after.last().orderKey > before.last().orderKey)
        }

    @Test fun movingAMiddleGroupAcrossItsNeighbourWritesOneKey() =
        runTest {
            val (g1, g2, g3, g4) = groups(4).let { listOf(it[0], it[1], it[2], it[3]) }
            val before = rows()
            assertEquals(Outcome.Success(Unit), repo.reorder(listOf(g1, g3, g2, g4)))
            val after = rows()
            assertEquals(setOf(g3), changedKeys(before, after))
            assertEquals(listOf(g1, g3, g2, g4), after.map { it.id })
        }

    @Test fun repeatedMovesEachWriteOneKey() =
        runTest {
            val (g1, g2, g3, g4) = groups(4).let { listOf(it[0], it[1], it[2], it[3]) }
            var before = rows()
            assertEquals(Outcome.Success(Unit), repo.reorder(listOf(g4, g1, g2, g3)))
            var after = rows()
            assertEquals(setOf(g4), changedKeys(before, after))

            before = after
            assertEquals(Outcome.Success(Unit), repo.reorder(listOf(g1, g4, g2, g3)))
            after = rows()
            assertEquals(setOf(g1), changedKeys(before, after))
            assertEquals(listOf(g1, g4, g2, g3), after.map { it.id })
        }

    @Test fun reorderToCurrentOrderAndNonPermutationsWriteNothing() =
        runTest {
            val ids = groups(3)
            val before = rows()
            clock.nowMs++
            assertEquals(Outcome.Success(Unit), repo.reorder(ids))
            assertEquals(Outcome.Failure(GroupError.NotFound), repo.reorder(ids.dropLast(1)))
            assertEquals(Outcome.Failure(GroupError.NotFound), repo.reorder(ids + 99L))
            assertEquals(Outcome.Failure(GroupError.NotFound), repo.reorder(ids.drop(1) + ids[0] + 99L))
            assertEquals(Outcome.Failure(GroupError.NotFound), repo.reorder(listOf(ids[0], ids[0], ids[2])))
            assertEquals(before, rows())
        }

    @Test fun equalNeighbourKeysTriggerARewriteOfTheWholeList() =
        runTest {
            val (g1, g2, g3) = groups(3).let { listOf(it[0], it[1], it[2]) }
            // 'a' sorts after the 0.x keys, so fix all three keys: g1 and g2 share 'a', g3 is 'b'.
            db.useWriterConnection {
                it.execSql(
                    "UPDATE podcast_group SET orderKey = CASE WHEN id = $g3 THEN 'b' ELSE 'a' END" +
                        " WHERE id IN ($g1, $g2, $g3)",
                )
            }
            // Moving g3 between the equal-keyed pair can only be resolved by a rewrite.
            assertEquals(Outcome.Success(Unit), repo.reorder(listOf(g1, g3, g2)))
            val after = rows()
            assertEquals(listOf(g1, g3, g2), after.map { it.id })
            assertEquals(listOf("0.25", "0.5", "0.75"), after.map { it.orderKey })
        }

    @Test fun keysBeyondTheProtocolLimitTriggerARewrite() =
        runTest {
            val (g1, g2, g3) = groups(3).let { listOf(it[0], it[1], it[2]) }
            val longKeys =
                GroupRepositoryImpl(
                    db,
                    clock,
                    OverlongKeys,
                    FakeGroupChannelSync(),
                    FakeRefreshController(),
                    FakeSnapshotScheduler(),
                    appScope,
                )
            assertEquals(Outcome.Success(Unit), longKeys.reorder(listOf(g1, g3, g2)))
            val after = rows()
            assertEquals(listOf(g1, g3, g2), after.map { it.id })
            assertEquals(listOf("0.25", "0.5", "0.75"), after.map { it.orderKey })
        }

    @Test fun rowsWithEqualKeysOrderDeterministicallyById() =
        runTest {
            val (g1, g2) = groups(2).let { listOf(it[0], it[1]) }
            db.useWriterConnection {
                it.execSql("UPDATE podcast_group SET orderKey = 'z' WHERE id IN ($g1, $g2)")
            }
            assertEquals(listOf(g1, g2), repo.observeGroups().first().map { it.id })
            assertEquals(listOf(g1, g2), rows().map { it.id })
        }

    @Test fun reorderStampsOnlyTheMovedRows() =
        runTest {
            val (g1, g2, g3) = groups(3).let { listOf(it[0], it[1], it[2]) }
            val before = rows()
            clock.nowMs++
            assertEquals(Outcome.Success(Unit), repo.reorder(listOf(g3, g1, g2)))
            val after = rows()
            for (row in after) {
                val old = before.single { it.id == row.id }
                if (row.id == g3) {
                    assertEquals(clock.now(), row.updatedAt)
                } else {
                    assertEquals(old.updatedAt, row.updatedAt)
                }
            }
        }

    @Test fun appendRewritesAndCapturesExistingGroupsAndMembersAtomically() =
        runTest {
            val g = db.groupDao().insert(groupEntity(orderKey = "a5"))
            val p = db.podcastDao().insertPodcast(podcastEntity())
            val q = db.podcastDao().insertPodcast(podcastEntity())
            db.groupDao().insertMember(memberEntity(g, p, "a5", addedAt = 7))
            db.syncStateDao().upsert(SyncStateEntity(enabled = true, nodeId = "0000000000000001"))
            val repo =
                GroupRepositoryImpl(
                    db,
                    clock,
                    LongAppendKeys,
                    FakeGroupChannelSync(),
                    FakeRefreshController(),
                    FakeSnapshotScheduler(),
                    backgroundScope,
                )
            db.useWriterConnection {
                it.execSql(
                    "CREATE TRIGGER reject_group BEFORE INSERT ON podcast_group" +
                        " BEGIN SELECT RAISE(ABORT, 'test'); END",
                )
            }
            assertFailsWith<Exception> { repo.create(GroupDraft("Next")) }
            assertEquals("a5", db.groupDao().groupById(g)?.orderKey)
            assertTrue(db.syncOutboxDao().rows().isEmpty())
            assertEquals(0, db.syncStateDao().get()?.hlc)
            db.useWriterConnection { it.execSql("DROP TRIGGER reject_group") }

            val next = assertIs<Outcome.Success<Long>>(repo.create(GroupDraft("Next"))).value
            assertEquals(ProtocolOrderKeys.rewrite(2), db.groupDao().groupsInOrder().map { it.orderKey })
            assertEquals(listOf(g, next), db.groupDao().groupsInOrder().map { it.id })
            assertEquals(Outcome.Success(Unit), repo.setMembers(g, setOf(p, q)))
            val members = db.groupDao().membersOf(g)
            assertEquals(ProtocolOrderKeys.rewrite(2), members.map { it.orderKey })
            assertEquals(7, members.first().addedAt)
            val rows = db.syncOutboxDao().rows()
            assertEquals(1, rows.count { it.coll == "group" && it.field == "ok" })
            assertEquals(2, rows.count { it.coll == "member" && it.field == "ok" })
        }

    @Test fun sqliteUsesBinaryKeyOrderAndLocalIdTiesForAllThreeLists() =
        runTest {
            val keys = listOf("a0z", "a0V", "b00", "Zz", "a0a", "a0V", "a0")
            val groups = keys.mapIndexed { i, key -> db.groupDao().insert(groupEntity(name = "G$i", orderKey = key)) }
            val group = groups.first()
            val podcasts = keys.map { db.podcastDao().insertPodcast(podcastEntity()) }
            val episodes = podcasts.map { db.episodeDao().insertStub(episodeEntity(podcastId = it)) }
            for (i in keys.indices) {
                db.groupDao().insertMember(memberEntity(group, podcasts[i], keys[i]))
                db.queueDao().insert(QueueEntryEntity(episodeId = episodes[i], orderKey = keys[i], addedAt = 1))
            }
            val indices = keys.indices.sortedWith(compareBy({ keys[it] }, { it }))
            assertEquals(indices.map(groups::get), db.groupDao().groupsInOrder().map { it.id })
            assertEquals(indices.map(podcasts::get), db.groupDao().membersOf(group).map { it.podcastId })
            assertEquals(indices.map(episodes::get), db.queueDao().entries().map { it.episodeId })
        }

    @Test fun groupReorderBetweenTiesCapturesEveryRewrittenKey() =
        runTest {
            val ids =
                (0..2).map {
                    db.groupDao().insert(groupEntity(name = "G$it", orderKey = if (it == 2) "b00" else "a0"))
                }
            db.withWriteTransaction {
                db.syncStateDao().upsert(SyncStateEntity(enabled = true, nodeId = "0000000000000001"))
            }
            val repo =
                GroupRepositoryImpl(
                    db,
                    clock,
                    ProtocolOrderKeys,
                    FakeGroupChannelSync(),
                    FakeRefreshController(),
                    FakeSnapshotScheduler(),
                    backgroundScope,
                )
            val desired = listOf(ids[0], ids[2], ids[1])
            assertEquals(Outcome.Success(Unit), repo.reorder(desired))
            assertEquals(desired, db.groupDao().groupsInOrder().map { it.id })
            val changed = db.groupDao().groupsInOrder().filter { it.orderKey != if (it.id == ids[2]) "b00" else "a0" }
            assertEquals(
                changed.map { it.uuid }.toSet(),
                db
                    .syncOutboxDao()
                    .rows()
                    .map { it.rid }
                    .toSet(),
            )
            assertTrue(db.syncOutboxDao().rows().all { it.field == "ok" })
        }

    private object LongAppendKeys : OrderKeys by ProtocolOrderKeys {
        override fun after(last: String?): String = "z".repeat(OrderKeys.MAX_LENGTH + 1)
    }

    /** `between` returns an over-limit key so every move must fall back to `rewrite`. */
    private object OverlongKeys : OrderKeys {
        override fun after(last: String?): String = TestOrderKeys.after(last)

        override fun between(
            previous: String?,
            next: String?,
        ): String = "z".repeat(OrderKeys.MAX_LENGTH + 1)

        override fun rewrite(count: Int): List<String> = TestOrderKeys.rewrite(count)
    }
}
