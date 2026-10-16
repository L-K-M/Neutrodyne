// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.repo.GroupRepositoryImpl
import ch.lkmc.neutrodyne.core.database.SqliteDriverBindings
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Real Room statements and transactions, with selections spanning three 500-ID chunks. */
class GroupMembershipChunksTest {
    private val driver = MembershipTraceDriver()
    private val clock = TestClock()
    private val db = TestDb.inMemory(driver = driver, clock = clock)
    private val repo = GroupRepositoryImpl(db, clock, TestOrderKeys)

    @AfterTest fun close() = db.close()

    @Test fun createValidatesAndSeedsAll1201Members() =
        runTest {
            val podcasts = seedPodcasts()
            driver.idCounts.clear()
            val group = created("Created", podcasts)
            assertEquals(podcasts, repo.observeMemberIds(group).first())
            assertEquals(podcasts.size, repo.observeGroup(group).first()?.memberCount)
            val members = db.groupDao().membersOf(group)
            assertTrue(members.zipWithNext().all { (a, b) -> a.orderKey < b.orderKey })
            assertEquals(listOf(500, 500, 201), driver.idCounts.toList())
        }

    @Test fun setMembersValidatesAndDeletesAcrossChunksKeepingUnchangedRows() =
        runTest {
            val podcasts = seedPodcasts()
            val group = created("Set", podcasts)
            val kept = db.groupDao().membersOf(group).last()
            clock.nowMs++
            driver.idCounts.clear()
            assertEquals(Outcome.Success(Unit), repo.setMembers(group, setOf(kept.podcastId)))
            assertEquals(listOf(kept), db.groupDao().membersOf(group))
            assertEquals(clock.now(), db.groupDao().groupById(group)?.updatedAt)
            assertBounded()
            driver.idCounts.clear()
            assertEquals(Outcome.Success(Unit), repo.setMembers(group, podcasts))
            assertEquals(podcasts, repo.observeMemberIds(group).first())
            assertEquals(kept, db.groupDao().membersOf(group).single { it.podcastId == kept.podcastId })
            assertBounded()
        }

    @Test fun applyMembershipValidatesAddsAndRemoves1201Podcasts() =
        runTest {
            val podcasts = seedPodcasts()
            val group = created("Apply")
            clock.nowMs++
            driver.idCounts.clear()
            assertEquals(Outcome.Success(Unit), repo.applyMembership(podcasts, setOf(group), emptySet()))
            assertEquals(podcasts, repo.observeMemberIds(group).first())
            assertEquals(clock.now(), db.groupDao().groupById(group)?.updatedAt)
            assertBounded()
            clock.nowMs++
            driver.idCounts.clear()
            assertEquals(Outcome.Success(Unit), repo.applyMembership(podcasts, emptySet(), setOf(group)))
            assertEquals(emptySet(), repo.observeMemberIds(group).first())
            assertEquals(clock.now(), db.groupDao().groupById(group)?.updatedAt)
            assertBounded()
        }

    @Test fun applyMembershipValidatesAndStamps1201Groups() =
        runTest {
            val podcast = seedPodcasts(1)
            val groups = seedGroups()
            clock.nowMs++
            driver.idCounts.clear()
            assertEquals(Outcome.Success(Unit), repo.applyMembership(podcast, groups, emptySet()))
            assertEquals(
                groups,
                repo
                    .observeGroupsOf(podcast.single())
                    .first()
                    .map { it.id }
                    .toSet(),
            )
            assertTrue(db.groupDao().groupsInOrder().all { it.updatedAt == clock.now() })
            assertBounded()
            val before = db.groupDao().groupsInOrder()
            clock.nowMs++
            assertEquals(Outcome.Success(Unit), repo.applyMembership(podcast, groups, emptySet()))
            assertEquals(before, db.groupDao().groupsInOrder())
        }

    @Test fun missingIdsInLaterValidationChunksWriteNothing() =
        runTest {
            val podcasts = seedPodcasts()
            val groups = seedGroups()
            val before = db.groupDao().groupsInOrder()
            assertEquals(Outcome.Failure(GroupError.NotFound), repo.create(GroupDraft("Missing"), podcasts + -1L))
            assertEquals(Outcome.Failure(GroupError.NotFound), repo.setMembers(groups.first(), podcasts + -1L))
            assertEquals(Outcome.Failure(GroupError.NotFound), repo.applyMembership(podcasts, groups + -1L, emptySet()))
            assertEquals(before, db.groupDao().groupsInOrder())
            assertEquals(emptyMap(), repo.observeMemberships().first())
            assertEquals(
                Outcome.Failure(GroupError.NotFound),
                repo.create(GroupDraft("Oversize"), (1L..32767L).toSet()),
            )
        }

    @Test fun laterDeleteChunkFailureRollsBackEarlierDeletes() =
        runTest {
            val podcasts = seedPodcasts()
            val group = created("Rollback", podcasts)
            val before = db.groupDao().membersOf(group)
            val stamp = db.groupDao().groupById(group)
            clock.nowMs++
            driver.failOnSecondStatementStartingWith = "DELETE FROM podcast_group_member"
            val error = assertFailsWith<SQLiteException> { repo.setMembers(group, emptySet()) }
            assertTrue(error.message.orEmpty().contains("later chunk fault"))
            assertEquals(2, driver.matchingStatements)
            assertEquals(before, db.groupDao().membersOf(group))
            assertEquals(stamp, db.groupDao().groupById(group))
        }

    @Test fun laterTimestampChunkFailureRollsBackMembershipsAndEarlierStamps() =
        runTest {
            val podcast = seedPodcasts(1)
            val groups = seedGroups()
            val before = db.groupDao().groupsInOrder()
            clock.nowMs++
            driver.failOnSecondStatementStartingWith = "UPDATE podcast_group SET updatedAt"
            assertFailsWith<SQLiteException> { repo.applyMembership(podcast, groups, emptySet()) }
            assertEquals(2, driver.matchingStatements)
            assertEquals(emptyMap(), repo.observeMemberships().first())
            assertEquals(before, db.groupDao().groupsInOrder())
        }

    private suspend fun seedPodcasts(count: Int = 1201): Set<Long> =
        db.withWriteTransaction {
            (1..count).map { db.podcastDao().insertPodcast(podcastEntity(title = "Podcast $it")) }.toSet()
        }

    private suspend fun seedGroups(): Set<Long> =
        db.withWriteTransaction {
            (1..1201).map { db.groupDao().insert(groupEntity(name = "Group $it", orderKey = "a$it")) }.toSet()
        }

    private suspend fun created(
        name: String,
        members: Set<Long> = emptySet(),
    ): Long = assertIs<Outcome.Success<Long>>(repo.create(GroupDraft(name), members)).value

    private fun assertBounded() {
        assertTrue(driver.idCounts.isNotEmpty())
        assertTrue(driver.idCounts.all { it <= 500 }, "IN list sizes: ${driver.idCounts}")
    }
}

/** Counts only the ID list: delete/timestamp statements also bind a scalar group ID or time. */
private class MembershipTraceDriver : SQLiteDriver {
    private val delegate = SqliteDriverBindings.sqliteDriver()
    val idCounts = CopyOnWriteArrayList<Int>()
    var failOnSecondStatementStartingWith: String? = null
    var matchingStatements = 0
        private set

    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        return object : SQLiteConnection by connection {
            override fun prepare(sql: String): SQLiteStatement {
                val statement = connection.prepare(sql)
                val tracksIds =
                    sql.contains(" IN (") &&
                        (
                            sql.startsWith("SELECT id FROM podcast WHERE id IN") ||
                                sql.startsWith("SELECT id FROM podcast_group WHERE id IN") ||
                                sql.startsWith("DELETE FROM podcast_group_member") ||
                                sql.startsWith("UPDATE podcast_group SET updatedAt")
                        )
                if (!tracksIds) return statement

                val idCount = sql.substringAfter(" IN (").substringBefore(')').count { it == '?' }
                return object : SQLiteStatement by statement {
                    private var started = false

                    override fun step(): Boolean {
                        // Room caches prepared statements; count each execution, not preparation.
                        if (!started) {
                            started = true
                            idCounts += idCount
                            if (failOnSecondStatementStartingWith?.let(sql::startsWith) == true) {
                                matchingStatements++
                                if (matchingStatements == 2) throw SQLiteException("later chunk fault")
                            }
                        }
                        return statement.step()
                    }

                    override fun reset() {
                        statement.reset()
                        started = false
                    }
                }
            }
        }
    }
}
