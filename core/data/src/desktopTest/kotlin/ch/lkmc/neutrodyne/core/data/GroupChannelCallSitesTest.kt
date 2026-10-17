// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.repo.GroupRepositoryImpl
import ch.lkmc.neutrodyne.core.data.settings.ScopeSettingsRepositoryImpl
import ch.lkmc.neutrodyne.core.domain.DeletedGroupToken
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.RefreshController
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.GroupEdit
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Exercise common repository writes over Room, with a recording domain port rather than Android APIs. */
class GroupChannelCallSitesTest {
    private val db = TestDb.inMemory()
    private val channels = FakeGroupChannelSync()
    private val refresh = FakeRefreshController()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val groups = GroupRepositoryImpl(db, TestClock(), TestOrderKeys, channels, refresh, scope)
    private val settings = ScopeSettingsRepositoryImpl(db, FakeSettingsRepository(), { refresh }, channels)

    @AfterTest
    fun close() {
        scope.cancel()
        db.close()
    }

    @Test
    fun createRenameDeleteAndUndoSweepAfterTheCommitAndOutsideTheUndoLock() =
        runTest {
            val observedNames = mutableListOf<String?>()
            val observedUndo = mutableListOf<Set<String>>()
            channels.onSync = {
                observedNames +=
                    db
                        .groupDao()
                        .groupsInOrder()
                        .singleOrNull()
                        ?.name
            }
            val id = assertIs<Outcome.Success<Long>>(groups.create(GroupDraft("Tech"))).value
            assertEquals(listOf("sync()"), channels.calls)
            assertEquals(listOf<String?>("Tech"), observedNames)

            assertIs<Outcome.Success<Unit>>(groups.update(id, GroupEdit.Rename("Science")))
            assertEquals(2, channels.calls.size)
            assertEquals(listOf<String?>("Tech", "Science"), observedNames)
            val uuid = assertNotNull(db.groupDao().groupById(id)).uuid

            channels.onSync = {
                observedNames += db.groupDao().groupById(id)?.name
                observedUndo += groups.pendingUndoUuids()
            }
            val token = assertIs<Outcome.Success<DeletedGroupToken>>(groups.delete(id)).value
            assertEquals(3, channels.calls.size)
            assertEquals(listOf("Tech", "Science", null), observedNames)
            assertEquals(listOf(setOf(uuid)), observedUndo)

            assertEquals(Outcome.Success(id), groups.undoDelete(token))
            assertEquals(List(4) { "sync()" }, channels.calls)
            assertEquals(listOf("Tech", "Science", null, "Science"), observedNames)
            assertEquals(listOf(setOf(uuid), emptySet()), observedUndo)
        }

    @Test
    fun intervalAndNotificationChangesKeepBothPostCommitEffects() =
        runTest {
            val id = assertIs<Outcome.Success<Long>>(groups.create(GroupDraft("Tech"))).value
            channels.calls.clear()
            assertIs<Outcome.Success<Unit>>(
                settings.updateGroup(id) { it.copy(refreshIntervalMinutes = 120, notifyNewEpisodes = true) },
            )
            assertEquals(listOf("reschedulePeriodic()"), refresh.calls)
            assertEquals(listOf("sync()"), channels.calls)
            val stored = assertNotNull(db.scopeSettingsDao().forGroup(id)).o
            assertEquals(120, stored.refreshIntervalMinutes)
            assertEquals(true, stored.notifyNewEpisodes)
        }

    @Test
    fun channelEffectsSurviveCallerCancellationAfterEachGroupCommit() =
        runTest {
            val id = assertIs<Outcome.Success<Long>>(groups.create(GroupDraft("Tech"))).value
            var token: DeletedGroupToken? = null
            val writes: List<suspend () -> Unit> =
                listOf(
                    { groups.create(GroupDraft("News")) },
                    { groups.update(id, GroupEdit.Rename("Science")) },
                    { settings.updateGroup(id) { it.copy(refreshIntervalMinutes = 120, notifyNewEpisodes = true) } },
                    { token = assertIs<Outcome.Success<DeletedGroupToken>>(groups.delete(id)).value },
                    { groups.undoDelete(assertNotNull(token)) },
                )
            for (write in writes) {
                lateinit var caller: Job
                var completed = false
                channels.onSync = {
                    caller.cancel()
                    yield()
                    groups.pendingUndoUuids()
                    completed = true
                }
                caller = launch(start = CoroutineStart.LAZY) { write() }
                caller.start()
                caller.join()
                assertEquals(true, completed)
            }
            assertEquals(List(3) { "reschedulePeriodic()" }, refresh.calls)
        }

    @Test
    fun cancellationDuringReschedulingStillReconcilesCommittedChannels() =
        runTest {
            val podcast = db.podcastDao().insertPodcast(podcastEntity())
            var caller: Job? = null
            var rescheduled = false
            val controller =
                object : RefreshController by refresh {
                    override suspend fun reschedulePeriodic() {
                        caller?.cancel()
                        yield()
                        rescheduled = true
                    }
                }
            val hookedGroups = GroupRepositoryImpl(db, TestClock(), TestOrderKeys, channels, controller, scope)
            val hookedSettings = ScopeSettingsRepositoryImpl(db, FakeSettingsRepository(), { controller }, channels)
            val id = assertIs<Outcome.Success<Long>>(hookedGroups.create(GroupDraft("Tech"))).value
            val token = assertIs<Outcome.Success<DeletedGroupToken>>(hookedGroups.delete(id)).value
            val writes: List<suspend () -> Unit> =
                listOf(
                    { hookedGroups.create(GroupDraft("News"), setOf(podcast)) },
                    { hookedGroups.undoDelete(token) },
                    {
                        hookedSettings.updateGroup(
                            id,
                        ) { it.copy(refreshIntervalMinutes = 120, notifyNewEpisodes = true) }
                    },
                )
            for (write in writes) {
                var reconciled = false
                rescheduled = false
                channels.onSync = {
                    yield()
                    hookedGroups.pendingUndoUuids()
                    reconciled = true
                }
                caller = launch(start = CoroutineStart.LAZY) { write() }
                assertNotNull(caller).start()
                assertNotNull(caller).join()
                assertEquals(true, rescheduled)
                assertEquals(true, reconciled)
            }
        }

    @Test
    fun rejectedGroupWritesDoNotSweep() =
        runTest {
            assertEquals(Outcome.Failure(GroupError.NameEmpty), groups.create(GroupDraft("")))
            assertEquals(Outcome.Failure(GroupError.NotFound), groups.update(-1, GroupEdit.Rename("Missing")))
            assertEquals(Outcome.Failure(GroupError.NotFound), groups.delete(-1))
            assertEquals(Outcome.Failure(GroupError.UndoExpired), groups.undoDelete(DeletedGroupToken("missing")))
            assertEquals(emptyList(), channels.calls)
        }

    @Test
    fun groupSettingsSweepTheCommittedOwnValueButPodcastSettingsDoNot() =
        runTest {
            val id = assertIs<Outcome.Success<Long>>(groups.create(GroupDraft("Tech"))).value
            channels.calls.clear()
            val observed = mutableListOf<Boolean?>()
            channels.onSync = {
                observed +=
                    db
                        .scopeSettingsDao()
                        .forGroup(id)
                        ?.o
                        ?.notifyNewEpisodes
            }

            assertIs<Outcome.Success<Unit>>(settings.updateGroup(id) { it.copy(notifyNewEpisodes = true) })
            assertEquals(listOf("sync()"), channels.calls)
            assertEquals(listOf<Boolean?>(true), observed)

            assertIs<Outcome.Success<Unit>>(settings.updateGroup(id) { it.copy(notifyNewEpisodes = null) })
            assertEquals(List(2) { "sync()" }, channels.calls)
            assertEquals(listOf(true, null), observed)
            assertNull(db.scopeSettingsDao().forGroup(id))

            val podcast = db.podcastDao().insertPodcast(podcastEntity())
            assertIs<Outcome.Success<Unit>>(settings.updatePodcast(podcast) { it.copy(notifyNewEpisodes = true) })
            assertIs<Outcome.Failure<*>>(settings.updateGroup(id) { it.copy(refreshIntervalMinutes = -1) })
            assertEquals(List(2) { "sync()" }, channels.calls)
        }

    @Test
    fun channelFailuresDoNotReportCommittedGroupOrSettingsWritesAsFailed() =
        runTest {
            channels.onSync = { error("Notification service unavailable") }
            val id = assertIs<Outcome.Success<Long>>(groups.create(GroupDraft("Tech"))).value
            assertIs<Outcome.Success<Unit>>(groups.update(id, GroupEdit.Rename("Science")))
            assertIs<Outcome.Success<Unit>>(settings.updateGroup(id) { it.copy(notifyNewEpisodes = true) })
            assertEquals("Science", db.groupDao().groupById(id)?.name)
            assertEquals(
                true,
                db
                    .scopeSettingsDao()
                    .forGroup(id)
                    ?.o
                    ?.notifyNewEpisodes,
            )

            val token = assertIs<Outcome.Success<DeletedGroupToken>>(groups.delete(id)).value
            assertIs<Outcome.Success<Long>>(groups.undoDelete(token))
            assertNotNull(db.groupDao().groupById(id))
            assertEquals(List(5) { "sync()" }, channels.calls)
        }
}
