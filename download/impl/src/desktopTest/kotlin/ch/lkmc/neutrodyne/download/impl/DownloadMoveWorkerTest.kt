// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.domain.MoveResult
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.download.api.DownloadNotice
import ch.lkmc.neutrodyne.download.api.RootChangeMode
import ch.lkmc.neutrodyne.download.api.RootChangeResult
import ch.lkmc.neutrodyne.download.api.RootKind
import ch.lkmc.neutrodyne.download.api.StorageRootInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.IOException
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AC13 runs the common algorithm against real Room transactions and Okio handles. */
class DownloadMoveWorkerTest {
    @Test fun copySyncVerifyRenameCommitIndexAndJournalDelete() =
        engineTest {
            val engine = this
            val f = MoveRig(engine)
            val id = f.completed(size = 600_000).single()
            f.beforePublish = {
                assertEquals("old", engine.row(id).rootId)
                assertNotNull(engine.files.stat("new", ".partial/$id.move"))
                assertNotNull(engine.files.stat("old", f.oldPath(id)))
            }
            assertEquals(MoveResult.COMPLETE, f.run())
            val row = engine.row(id)
            assertEquals("new", row.rootId)
            assertTrue(checkNotNull(row.finalUri).startsWith("file:///new/"))
            assertEquals(row.finalUri, f.base.index.localUriOrNull(id))
            f.assertBytes(id, mediaBytes(600_000))
            assertNull(engine.files.stat("old", f.oldPath(id)))
            assertNull(engine.files.stat("new", ".partial/$id.move"))
            assertTrue(engine.faults.operations.indexOf("sync") < engine.faults.operations.indexOf("move"))
            assertTrue(engine.faults.largestWrite <= TRANSFER_BUFFER_BYTES)
            assertTrue(
                f.base.deletes
                    .entries()
                    .isEmpty(),
            )
            assertEquals(600_000L + 200L * 1024 * 1024, engine.roots.required)
            assertEquals("", f.target())
        }

    @Test fun currentCopyCommitsButSourceDeletionWaitsForSessionChange() =
        engineTest {
            val engine = this
            val f = MoveRig(engine)
            val id = f.completed().single()
            f.base.current(id)
            assertEquals(MoveResult.COMPLETE, f.run())
            assertEquals("new", engine.row(id).rootId)
            assertNotNull(engine.files.stat("old", f.oldPath(id)))
            assertTrue(
                f.base.deletes
                    .entries()
                    .any { it.episodeId == id },
            )
            f.base.current(null)
            engine.engine.claimMutex.lock()
            try {
                f.base.deletes.drain(null)
            } finally {
                engine.engine.claimMutex.unlock()
            }
            assertNull(engine.files.stat("old", f.oldPath(id)))
        }

    @Test fun unavailableFirstBatchDoesNotHideLaterFilesAndOnlyUnavailableEndsMove() =
        engineTest {
            val engine = this
            val f = MoveRig(engine)
            val unavailable = f.completed(20)
            val available = f.completed().single()
            engine.roots.available += "third"
            engine.faults.fake.createDirectories("/third".toPath())
            val row = engine.row(available)
            engine.files.moveBetweenRoots(
                "old",
                checkNotNull(row.relativePath),
                "third",
                checkNotNull(row.relativePath).also {
                    engine.files.createDirectories("third", it.substringBeforeLast('/'))
                },
            )
            engine.dao.updateUnclaimed(row.copy(rootId = "third"), DownloadState.COMPLETED)
            engine.roots.available -= "old"
            assertEquals(MoveResult.COMPLETE, f.run())
            for (id in unavailable) assertEquals("old", engine.row(id).rootId)
            assertEquals("new", engine.row(available).rootId)
            assertEquals("", f.target())
        }

    @Test fun lowSpaceKeepsRowsSourceAndTargetAndShowsNotice() =
        engineTest {
            val engine = this
            val f = MoveRig(engine)
            val id = f.completed().single()
            engine.roots.failure = StorageFailure.FULL
            assertEquals(MoveResult.STORAGE_FULL, f.run())
            assertEquals("old", engine.row(id).rootId)
            assertEquals("new", f.target())
            assertNotNull(engine.files.stat("old", f.oldPath(id)))
            assertTrue(
                DownloadNotice.STORAGE_LOW in
                    f.base.storage
                        .observeNotices()
                        .first(),
            )
            assertTrue(
                DownloadNotice.MOVE_IN_PROGRESS in
                    f.base.controller
                        .observeAll()
                        .first()
                        .notices,
            )
            engine.roots.failure = null
            assertEquals(MoveResult.COMPLETE, f.run())
            assertFalse(f.base.quota.moveStorageLow.value)
        }

    @Test fun deadlineDuringCopyLeavesIntentAndNextRunSweepsAndRestarts() =
        engineTest {
            val engine = this
            val f = MoveRig(engine)
            val id = f.completed(size = 600_000).single()
            f.afterAppend = { f.clock.elapsedMs = 100 }
            assertEquals(MoveResult.DEADLINE, f.run(deadline = 100))
            assertEquals("old", engine.row(id).rootId)
            assertEquals(TRANSFER_BUFFER_BYTES.toLong(), engine.files.stat("new", ".partial/$id.move")?.bytes)
            assertEquals("new", f.target())
            f.afterAppend = {}
            assertEquals(MoveResult.COMPLETE, f.run())
            f.assertBytes(id, mediaBytes(600_000))
        }

    @Test fun syncFailureAndLengthMismatchNeverPublish() =
        runTest {
            for (fault in listOf("sync", "length")) {
                EngineRig(this).use { engine ->
                    val f = MoveRig(engine)
                    val id = f.completed().single()
                    if (fault == "sync") {
                        engine.faults.syncFailure = IOException("sync failed")
                    } else {
                        f.shortenOutput = true
                    }
                    assertEquals(MoveResult.PENDING, f.run())
                    assertEquals("old", engine.row(id).rootId)
                    assertNotNull(engine.files.stat("old", f.oldPath(id)))
                    assertEquals("new", f.target())
                    assertTrue(engine.faults.operations.none { it == "move" })
                }
            }
        }

    @Test fun journalFailureLeavesOldRowAndSourceAndRetriesSafely() =
        engineTest {
            val engine = this
            val f = MoveRig(engine)
            val id = f.completed().single()
            f.base.store.fail = true
            assertEquals(MoveResult.PENDING, f.run())
            assertEquals("old", engine.row(id).rootId)
            assertNotNull(engine.files.stat("old", f.oldPath(id)))
            assertTrue(engine.faults.operations.none { it == "move" })
            f.base.store.fail = false
            assertEquals(MoveResult.COMPLETE, f.run())
        }

    @Test fun rowRemovedDuringCopyDoesNotResurrectOrLeaveFinalCopy() =
        engineTest {
            val engine = this
            val f = MoveRig(engine)
            val id = f.completed().single()
            f.beforePublish = { engine.dao.removeUnclaimed(listOf(id)) }
            assertEquals(MoveResult.COMPLETE, f.run())
            assertEquals("", f.target())
            assertNull(engine.dao.byEpisode(id))
            assertNull(engine.files.stat("new", ".partial/$id.move"))
            assertTrue(engine.faults.operations.none { it == "move" })
        }

    @Test fun newerRootIntentDuringCopyCannotBeClearedOrPublishedByOldRun() =
        engineTest {
            val engine = this
            val f = MoveRig(engine)
            val id = f.completed().single()
            f.beforePublish = { f.base.store.set(DownloadSettingKeys.MOVE_TARGET, "third") }
            assertEquals(MoveResult.COMPLETE, f.run())
            assertEquals("third", f.target())
            assertEquals("old", engine.row(id).rootId)
        }

    @Test fun rootChangePersistsIntentAndLeaveInPlaceClearsItWithoutMoving() =
        engineTest {
            val engine = this
            val f = MoveRig(engine)
            val id = f.completed().single()
            assertEquals(
                RootChangeResult.Applied,
                f.base.controller.changeRoot("new", RootChangeMode.MOVE_EXISTING),
            )
            assertEquals("new", f.target())
            assertEquals("new", f.base.store.get(DownloadSettingKeys.ROOT_ID))
            assertEquals(
                RootChangeResult.Applied,
                f.base.controller.changeRoot("old", RootChangeMode.NEW_DOWNLOADS_ONLY),
            )
            assertEquals("", f.target())
            assertEquals(MoveResult.COMPLETE, f.run())
            assertEquals("old", engine.row(id).rootId)
            assertTrue(engine.faults.operations.none { it == "move" })
            assertEquals(
                RootChangeResult.Unavailable,
                f.base.controller.changeRoot("absent", RootChangeMode.MOVE_EXISTING),
            )
            assertEquals("old", f.base.store.get(DownloadSettingKeys.ROOT_ID))
        }

    @Test fun sweepsOnlyMoveScratchAndRemovesOnlyEmptyPodcastFolders() =
        engineTest {
            val engine = this
            val f = MoveRig(engine)
            f.completed()
            f.write("new", ".partial/999.move", mediaBytes())
            f.write("new", ".partial/999.part", mediaBytes())
            f.write("old", "notes/keep.txt", mediaBytes())
            assertEquals(MoveResult.COMPLETE, f.run())
            assertNull(engine.files.stat("new", ".partial/999.move"))
            assertNotNull(engine.files.stat("new", ".partial/999.part"))
            assertNotNull(engine.files.stat("old", "notes/keep.txt"))
            assertNull(engine.files.stat("old", "Podcast [p1]"))
            assertNotNull(engine.files.stat("old", ""))
        }
}

internal class MoveRig(
    val engine: EngineRig,
    val sameStore: Boolean = false,
) {
    val base = CleanupRig(engine)
    val clock = TestClock()
    var beforePublish: suspend () -> Unit = {}
    var afterAppend: suspend () -> Unit = {}
    var shortenOutput = false
    val roots =
        object : StorageRoots by engine.roots {
            override suspend fun list() =
                (engine.roots.available + "old" + "new").map {
                    StorageRootInfo(it, RootKind.DESKTOP_FOLDER, null, null, it in engine.roots.available)
                }
        }
    val files =
        object : DownloadFileSystem by engine.files {
            override suspend fun sameStore(
                sourceRoot: String,
                targetRoot: String,
            ) = sameStore

            override suspend fun openPart(
                rootId: String,
                path: String,
            ): PartFile {
                val part = engine.files.openPart(rootId, path)
                if (!path.endsWith(".move")) return part
                return object : PartFile by part {
                    override suspend fun append(
                        buffer: ByteArray,
                        count: Int,
                    ) {
                        part.append(buffer, count)
                        afterAppend()
                    }

                    override suspend fun sync() {
                        part.sync()
                        if (shortenOutput) part.truncate(part.length() - 1)
                        beforePublish()
                    }
                }
            }
        }
    val mover =
        DownloadMoverImpl(
            engine.db,
            files,
            roots,
            base.store,
            base.index,
            base.deletes,
            engine.engine.claimMutex,
            clock,
            base.quota,
            io = engine.dispatcher,
        )

    suspend fun completed(
        count: Int = 1,
        size: Int = 4096,
    ): List<Long> {
        base.store.set(DownloadSettingKeys.MOVE_TARGET, "new")
        val ids = base.completed(count)
        for (id in ids) {
            write("old", oldPath(id), mediaBytes(size))
            val row = engine.row(id)
            engine.dao.updateUnclaimed(row.copy(totalBytes = size.toLong()), DownloadState.COMPLETED)
        }
        return ids
    }

    fun oldPath(id: Long) = base.path(id)

    suspend fun target() = base.store.get(DownloadSettingKeys.MOVE_TARGET)

    suspend fun run(deadline: Long? = null) = mover.run("new", deadline)

    suspend fun write(
        root: String,
        path: String,
        bytes: ByteArray,
    ) {
        val absolute = "/$root/$path".toPath()
        engine.faults.fake.createDirectories(checkNotNull(absolute.parent))
        engine.faults.fake.write(absolute) { write(bytes) }
    }

    suspend fun assertBytes(
        id: Long,
        bytes: ByteArray,
    ) {
        val row = engine.row(id)
        val part = engine.files.openPart(row.rootId, checkNotNull(row.relativePath))
        try {
            assertContentEquals(bytes, part.contents())
        } finally {
            part.close()
        }
    }
}
