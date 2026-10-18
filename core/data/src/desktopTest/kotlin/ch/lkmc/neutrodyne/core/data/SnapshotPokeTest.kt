// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.repo.GroupRepositoryImpl
import ch.lkmc.neutrodyne.core.data.settings.ScopeSettingsRepositoryImpl
import ch.lkmc.neutrodyne.core.domain.DeletedGroupToken
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.SnapshotScheduler
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.GroupEdit
import ch.lkmc.neutrodyne.core.model.SettingOverrides
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSnapshotScheduler
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * 05's Snapshot production work table: `SnapshotScheduler.requestSoon` is owed by every committed
 * group, membership or scope-settings write, and by none that failed validation, rolled back or
 * wrote nothing. Import `DONE`, restore-finished and subscribe/unsubscribe pokes live elsewhere
 * (`ImportFetchRunner`, `RestoreRunner`, the Android library-shape watcher).
 */
class SnapshotPokeTest {
    private val db = TestDb.inMemory()
    private val channels = FakeGroupChannelSync()
    private val refresh = FakeRefreshController()
    private val snapshots = FakeSnapshotScheduler()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val groups = GroupRepositoryImpl(db, TestClock(), TestOrderKeys, channels, refresh, snapshots, scope)
    private val settings = ScopeSettingsRepositoryImpl(db, FakeSettingsRepository(), { refresh }, channels, snapshots)

    @AfterTest
    fun close() {
        scope.cancel()
        db.close()
    }

    @Test
    fun everyCommittedGroupWriteRequestsASnapshot() =
        runTest {
            val podcast = db.podcastDao().insertPodcast(podcastEntity())
            val id = assertIs<Outcome.Success<Long>>(groups.create(GroupDraft("Tech"), setOf(podcast))).value
            assertEquals(1, snapshots.requestSoonCalls, "create")

            assertIs<Outcome.Success<Unit>>(groups.update(id, GroupEdit.Rename("Science")))
            assertEquals(2, snapshots.requestSoonCalls, "rename")
            assertIs<Outcome.Success<Unit>>(groups.update(id, GroupEdit.Look(0xFF112233.toInt(), null)))
            assertEquals(3, snapshots.requestSoonCalls, "look")
            assertIs<Outcome.Success<Unit>>(groups.update(id, GroupEdit.ShowAsTab(false)))
            assertEquals(4, snapshots.requestSoonCalls, "edit")

            val second = assertIs<Outcome.Success<Long>>(groups.create(GroupDraft("News"))).value
            assertEquals(5, snapshots.requestSoonCalls)
            assertIs<Outcome.Success<Unit>>(groups.reorder(listOf(second, id)))
            assertEquals(6, snapshots.requestSoonCalls, "reorder")

            assertIs<Outcome.Success<Unit>>(groups.setMembers(second, setOf(podcast)))
            assertEquals(7, snapshots.requestSoonCalls, "setMembers")
            assertIs<Outcome.Success<Unit>>(
                groups.applyMembership(setOf(podcast), add = emptySet(), remove = setOf(second)),
            )
            assertEquals(8, snapshots.requestSoonCalls, "applyMembership")

            val token = assertIs<Outcome.Success<DeletedGroupToken>>(groups.delete(id)).value
            assertEquals(9, snapshots.requestSoonCalls, "delete")
            assertIs<Outcome.Success<Long>>(groups.undoDelete(token))
            assertEquals(10, snapshots.requestSoonCalls, "undo")
        }

    @Test
    fun committedScopeWritesRequestASnapshot() =
        runTest {
            val podcast = db.podcastDao().insertPodcast(podcastEntity())
            val id = assertIs<Outcome.Success<Long>>(groups.create(GroupDraft("Tech"))).value
            val committed = snapshots.requestSoonCalls

            assertIs<Outcome.Success<Unit>>(settings.updatePodcast(podcast) { it.copy(notifyNewEpisodes = true) })
            assertEquals(committed + 1, snapshots.requestSoonCalls, "podcast override")
            assertIs<Outcome.Success<Unit>>(settings.updateGroup(id) { it.copy(refreshIntervalMinutes = 120) })
            assertEquals(committed + 2, snapshots.requestSoonCalls, "group override")
            // Clearing the last override deletes the row: a durable mutation as well.
            assertIs<Outcome.Success<Unit>>(settings.updateGroup(id) { SettingOverrides() })
            assertEquals(committed + 3, snapshots.requestSoonCalls, "override row delete")
        }

    @Test
    fun rejectedAndNoOpWritesNeverRequestASnapshot() =
        runTest {
            val podcast = db.podcastDao().insertPodcast(podcastEntity())
            val id = assertIs<Outcome.Success<Long>>(groups.create(GroupDraft("Tech"), setOf(podcast))).value
            val committed = snapshots.requestSoonCalls

            assertEquals(Outcome.Failure(GroupError.NameEmpty), groups.create(GroupDraft("")))
            assertEquals(Outcome.Failure(GroupError.NameTaken(id)), groups.create(GroupDraft("tech")))
            assertEquals(Outcome.Failure(GroupError.NotFound), groups.update(-1, GroupEdit.Rename("Gone")))
            assertEquals(Outcome.Failure(GroupError.NotFound), groups.reorder(listOf(id, -1)))
            assertEquals(Outcome.Failure(GroupError.NotFound), groups.setMembers(-1, setOf(podcast)))
            assertEquals(Outcome.Failure(GroupError.NotFound), groups.delete(-1))
            assertEquals(Outcome.Failure(GroupError.UndoExpired), groups.undoDelete(DeletedGroupToken("missing")))
            assertIs<Outcome.Failure<*>>(settings.updateGroup(id) { it.copy(refreshIntervalMinutes = -1) })

            // Successful no-ops write nothing durable, so they owe no poke either.
            assertIs<Outcome.Success<Unit>>(groups.reorder(listOf(id)))
            assertIs<Outcome.Success<Unit>>(groups.setMembers(id, setOf(podcast)))
            assertIs<Outcome.Success<Unit>>(settings.updatePodcast(-1) { it.copy(notifyNewEpisodes = true) })
            assertIs<Outcome.Success<Unit>>(settings.updateGroup(-1) { it.copy(notifyNewEpisodes = true) })

            assertEquals(committed, snapshots.requestSoonCalls)
        }

    @Test
    fun aWriteWhoseTransactionThrowsRequestsNothing() =
        runTest {
            val podcast = db.podcastDao().insertPodcast(podcastEntity())
            assertFailsWith<IllegalStateException> {
                settings.updatePodcast(podcast) { error("screen gone") }
            }
            assertEquals(0, snapshots.requestSoonCalls)
        }

    @Test
    fun aFailedSnapshotRequestNeverFailsTheCommittedWrite() =
        runTest {
            val podcast = db.podcastDao().insertPodcast(podcastEntity())
            val failing =
                object : SnapshotScheduler {
                    var calls = 0

                    override suspend fun requestSoon() {
                        calls++
                        throw IllegalStateException("WorkManager unavailable")
                    }

                    override suspend fun cancelScheduled() = Unit
                }
            val failingGroups = GroupRepositoryImpl(db, TestClock(), TestOrderKeys, channels, refresh, failing, scope)
            val failingSettings =
                ScopeSettingsRepositoryImpl(db, FakeSettingsRepository(), { refresh }, channels, failing)

            val id = assertIs<Outcome.Success<Long>>(failingGroups.create(GroupDraft("Tech"))).value
            assertIs<Outcome.Success<Unit>>(failingGroups.update(id, GroupEdit.Rename("Science")))
            assertIs<Outcome.Success<Unit>>(
                failingSettings.updatePodcast(podcast) { it.copy(notifyNewEpisodes = true) },
            )

            assertEquals("Science", db.groupDao().groupById(id)?.name)
            assertEquals(3, failing.calls)
        }
}
