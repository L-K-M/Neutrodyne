// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.domain.MoveResult
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.download.api.RootChangeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class DesktopMoveTest {
    @Test fun boundMoveLaneWaitsForStartupAndDefersThePlayingRename() =
        engineTest {
            val engine = this
            val f = MoveRig(engine, sameStore = true)
            val id = f.completed().single()
            f.base.current(id)
            val activity = DesktopDownloadActivity()
            val events = mutableListOf<String>()
            val startup =
                DesktopDownloadStartup {
                    events += "reconcile"
                    f.base.reconciler.reconcile()
                    events += "planner"
                }
            val lane =
                DesktopMoveLane(f.base.store, {
                    assertEquals(listOf("reconcile", "planner"), events)
                    f.mover.run(it)
                }, startup::ensureStarted)
            assertEquals("downloads-move", lane.name)
            assertTrue(activity.observeBusy(f.base.store).first())
            lane.run(Instant.fromEpochMilliseconds(1))
            assertEquals("old", engine.row(id).rootId)
            assertEquals("new", f.target())
            f.base.current(null)
            lane.run(Instant.fromEpochMilliseconds(2))
            assertEquals("new", engine.row(id).rootId)
            assertEquals("", f.target())
            assertEquals(listOf("reconcile", "planner"), events)
            kotlin.test.assertFalse(activity.observeBusy(f.base.store).first())
        }

    @Test fun sameStoreRenamesWithoutCopySyncOrSpaceReservation() =
        engineTest {
            val engine = this
            val f = MoveRig(engine, sameStore = true)
            val id = f.completed().single()
            engine.roots.failure = StorageFailure.FULL
            assertEquals(MoveResult.COMPLETE, f.run())
            f.assertBytes(id, mediaBytes())
            assertEquals(0L, engine.roots.required)
            assertTrue(engine.faults.operations.none { it == "write" || it == "sync" })
            assertNull(engine.files.stat("old", f.oldPath(id)))
        }

    @Test fun sameStorePlayingFileRemainsPendingUntilSessionChanges() =
        engineTest {
            val engine = this
            val f = MoveRig(engine, sameStore = true)
            val ids = f.completed(22)
            f.base.current(ids[0])
            assertEquals(MoveResult.PENDING, f.run())
            assertEquals("old", engine.row(ids[0]).rootId)
            assertNotNull(engine.files.stat("old", f.oldPath(ids[0])))
            for (id in ids.drop(1)) assertEquals("new", engine.row(id).rootId)
            assertEquals("new", f.target())
            f.base.current(null)
            assertEquals(MoveResult.COMPLETE, f.run())
            assertEquals("new", engine.row(ids[0]).rootId)
            assertEquals("", f.target())
        }

    @Test fun failedTransactionRestoresSourceForBothMoveMethods() =
        runTest {
            for (sameStore in listOf(false, true)) {
                EngineRig(this).use { engine ->
                    val f = MoveRig(engine, sameStore = sameStore)
                    val id = f.completed().single()
                    engine.db.useWriterConnection { c ->
                        c.usePrepared(
                            "CREATE TRIGGER reject_move BEFORE UPDATE OF rootId ON download" +
                                " BEGIN SELECT RAISE(ABORT, 'test'); END",
                        ) { it.step() }
                    }
                    assertFails { f.run() }
                    assertEquals("old", engine.row(id).rootId)
                    assertNotNull(engine.files.stat("old", f.oldPath(id)))
                    assertEquals("new", f.target())
                    assertEquals(if (sameStore) 2 else 1, engine.faults.operations.count { it == "move" })
                    val folders = engine.files.list("new", "").filter { it.directory }
                    assertTrue(folders.all { engine.files.list("new", it.path).isEmpty() })
                }
            }
        }

    @Test fun crossStoreMoveKeepsTargetOnQuitAndContinuesNextRun() =
        engineTest {
            val engine = this
            val f = MoveRig(engine)
            val ids = f.completed(2, size = 600_000)
            val entered = CompletableDeferred<Unit>()
            f.afterAppend = {
                entered.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
            val lane = DesktopMoveLane(f.base.store, f.mover)
            val run = async { lane.run(Instant.fromEpochMilliseconds(0)) }
            entered.await()
            run.cancelAndJoin()
            assertEquals("new", f.target())
            for (id in ids) assertEquals("old", engine.row(id).rootId)
            f.afterAppend = {}
            f.clock.elapsedMs = Long.MAX_VALUE
            lane.run(Instant.fromEpochMilliseconds(0))
            for (id in ids) {
                assertEquals("new", engine.row(id).rootId)
                f.assertBytes(id, mediaBytes(600_000))
            }
            assertEquals("", f.target())
        }

    @Test fun crashAfterRenameBeforeCommitRecoversEvenAfterReconcileMarkedMissing() =
        engineTest {
            val engine = this
            val f = MoveRig(engine, sameStore = true)
            val id = f.completed().single()
            val original = engine.row(id)
            assertEquals(MoveResult.COMPLETE, f.run())
            engine.dao.updateUnclaimed(original, DownloadState.COMPLETED)
            f.base.store.set(DownloadSettingKeys.MOVE_TARGET, "new")
            f.base.reconciler.reconcile()
            assertEquals(DownloadState.MISSING, engine.row(id).state)
            assertEquals(MoveResult.COMPLETE, f.run())
            assertEquals("new", engine.row(id).rootId)
            assertEquals(DownloadState.COMPLETED, engine.row(id).state)
            f.assertBytes(id, mediaBytes())
        }

    @Test fun leaveThemWhereTheyAreMakesLaneIdle() =
        engineTest {
            val engine = this
            val f = MoveRig(engine, sameStore = true)
            val id = f.completed().single()
            f.base.controller.changeRoot("new", RootChangeMode.NEW_DOWNLOADS_ONLY)
            DesktopMoveLane(f.base.store, f.mover).run(Instant.fromEpochMilliseconds(0))
            assertEquals("old", engine.row(id).rootId)
            assertTrue(engine.faults.operations.none { it == "move" })
            assertEquals("", f.base.store.get(DownloadSettingKeys.MOVE_TARGET))
        }
}
