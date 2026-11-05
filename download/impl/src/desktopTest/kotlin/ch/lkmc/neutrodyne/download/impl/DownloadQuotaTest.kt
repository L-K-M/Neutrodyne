// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.domain.CleanupReason
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.download.api.DownloadNotice
import ch.lkmc.neutrodyne.download.api.OrphanDeleteResult
import ch.lkmc.neutrodyne.download.api.RequestResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadQuotaTest {
    @Test fun reportsCompletedRowTotalsAcrossRootsAndActualPartLengths() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                val ids =
                    engine.seed(4) { row ->
                        when (row.requestedAt) {
                            1L -> {
                                row.copy(state = DownloadState.COMPLETED, totalBytes = 500)
                            }

                            2L -> {
                                row.copy(state = DownloadState.COMPLETED, totalBytes = 700, rootId = "new")
                            }

                            3L -> {
                                row.copy(
                                    state = DownloadState.PAUSED,
                                    totalBytes = 9000,
                                    downloadedBytes = 8000,
                                    tempPath = DownloadPaths.part(row.episodeId),
                                )
                            }

                            else -> {
                                row.copy(state = DownloadState.MISSING, totalBytes = 4000)
                            }
                        }
                    }
                f.write(DownloadPaths.part(ids[2]))
                f.write("Podcast [p1]/Unknown [e999].mp3")
                f.reconciler.sweepFiles()
                val usage = f.storage.observeStorage().first()
                assertEquals(1200L, usage.usedBytes)
                assertEquals(4096L, usage.partialBytes)
                assertEquals(4096L, usage.orphanBytes)
                assertNull(usage.capBytes)
                assertFalse(usage.autoBlockedByCap)
                f.settings.set(DownloadSettingKeys.STORAGE_CAP_MB, 1024)
                assertTrue(f.quota.admitsAuto(engine.dao.allRows(), CAP_BYTES - 1200))
            }
        }

    @Test fun capChangesAreObservedAndUnlimitedNeverBlocksAdmission() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                engine.seed { it.copy(state = DownloadState.COMPLETED, totalBytes = CAP_BYTES) }
                assertNull(
                    f.storage
                        .observeStorage()
                        .first()
                        .capBytes,
                )
                f.settings.set(DownloadSettingKeys.STORAGE_CAP_MB, 1024)
                val capped = f.storage.observeStorage().first()
                assertEquals(CAP_BYTES, capped.capBytes)
                assertTrue(capped.autoBlockedByCap)
                f.settings.set(DownloadSettingKeys.STORAGE_CAP_MB, 0)
                assertTrue(f.quota.admitsAuto(engine.dao.allRows(), Long.MAX_VALUE))
                assertFalse(
                    f.storage
                        .observeStorage()
                        .first()
                        .autoBlockedByCap,
                )
            }
        }

    @Test fun capReservesRemainingAutoBytesAndAcceptsExactFit() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                f.settings.set(DownloadSettingKeys.STORAGE_CAP_MB, 1024)
                engine.seed { it.copy(state = DownloadState.COMPLETED, totalBytes = CAP_BYTES - 100) }
                engine.seed {
                    it.copy(
                        lane = DownloadLane.AUTO,
                        state = DownloadState.DOWNLOADING,
                        totalBytes = 80,
                        downloadedBytes = 30,
                    )
                }
                val rows = engine.dao.allRows()
                assertTrue(f.quota.admitsAuto(rows, 50))
                assertFalse(f.quota.admitsAuto(rows, 51))
                assertTrue(
                    f.storage
                        .observeStorage()
                        .first()
                        .autoBlockedByCap,
                )
                assertEquals(51L, f.quota.refusedEstimate.value)
                f.quota.resetAdmission()
                assertFalse(
                    f.storage
                        .observeStorage()
                        .first()
                        .autoBlockedByCap,
                )
            }
        }

    @Test fun unknownAutoSizesReserveOneHundredFiftyBinaryMegabytes() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                f.settings.set(DownloadSettingKeys.STORAGE_CAP_MB, 1024)
                engine.seed { it.copy(state = DownloadState.COMPLETED, totalBytes = CAP_BYTES - 300 * MB) }
                engine.seed { it.copy(lane = DownloadLane.AUTO, totalBytes = null, estimatedBytes = null) }
                assertTrue(f.quota.admitsAuto(engine.dao.allRows(), null))
                engine.seed {
                    it.copy(
                        lane = DownloadLane.AUTO,
                        totalBytes = null,
                        estimatedBytes = null,
                        downloadedBytes = 0,
                    )
                }
                assertFalse(f.quota.admitsAuto(engine.dao.allRows(), null))
                assertEquals(150 * MB, f.quota.refusedEstimate.value)
            }
        }

    @Test fun reservesOnlyQueuedAndActiveAutoRowsAndNeverNegativeRemainders() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                f.settings.set(DownloadSettingKeys.STORAGE_CAP_MB, 1024)
                for (state in DownloadState.entries) {
                    engine.seed {
                        it.copy(lane = DownloadLane.AUTO, state = state, totalBytes = 100, downloadedBytes = 40)
                    }
                }
                val active =
                    listOf(
                        DownloadState.QUEUED,
                        DownloadState.RESOLVING,
                        DownloadState.DOWNLOADING,
                        DownloadState.VERIFYING,
                    )
                for (state in active) {
                    engine.seed { it.copy(lane = DownloadLane.MANUAL, state = state, totalBytes = CAP_BYTES) }
                }
                // COMPLETED contributes all 100; four reservable AUTO states contribute 60 each.
                assertTrue(f.quota.admitsAuto(engine.dao.allRows(), CAP_BYTES - 340))
                assertFalse(f.quota.admitsAuto(engine.dao.allRows(), CAP_BYTES - 339))
                val overshot =
                    engine
                        .seed {
                            it.copy(
                                lane = DownloadLane.AUTO,
                                totalBytes = 100,
                                downloadedBytes = 101,
                            )
                        }.single()
                assertTrue(f.quota.admitsAuto(listOf(engine.row(overshot)), CAP_BYTES))
            }
        }

    @Test fun capBlocksAutoAdmissionButManualRequestsStillQueueAndFilesAreNotEvicted() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                f.settings.set(DownloadSettingKeys.STORAGE_CAP_MB, 1024)
                val retained = f.completed(lane = DownloadLane.MANUAL).single()
                val row = engine.row(retained)
                engine.dao.updateUnclaimed(row.copy(totalBytes = CAP_BYTES), row.state)
                val requested = engine.seed().single()
                engine.dao.removeUnclaimed(listOf(requested))
                assertFalse(f.quota.admitsAuto(engine.dao.allRows(), 1))
                assertIs<RequestResult.Queued>(f.controller.request(listOf(requested), DownloadLane.MANUAL))
                assertEquals(DownloadLane.MANUAL, engine.row(requested).lane)
                assertEquals(DownloadState.QUEUED, engine.row(requested).state)
                f.planner.run(CleanupReason.STORAGE)
                assertNotNull(engine.dao.byEpisode(retained))
                assertNotNull(engine.files.stat("old", f.path(retained)))
            }
        }

    @Test fun cleanupClearsCapNoticeAndExplicitOrphanDeletionClearsOrphanNotice() =
        runTest {
            EngineRig(this).use { engine ->
                val f = CleanupRig(engine)
                f.settings.set(DownloadSettingKeys.STORAGE_CAP_MB, 1024)
                val id = f.completed().single()
                val row = engine.row(id)
                engine.dao.updateUnclaimed(row.copy(totalBytes = CAP_BYTES), row.state)
                f.write("Podcast [p1]/Unknown [e999].mp3")
                f.reconciler.sweepFiles()
                assertEquals(
                    setOf(DownloadNotice.CAP_REACHED, DownloadNotice.ORPHAN_FILES),
                    f.controller
                        .observeAll()
                        .first()
                        .notices,
                )
                assertFalse(f.quota.admitsAuto(engine.dao.allRows(), 1))
                f.state(id, playedAt = f.clock.now() - 24L * 60 * 60 * 1000)
                f.planner.run(CleanupReason.PLAYED)
                assertEquals(
                    setOf(DownloadNotice.ORPHAN_FILES),
                    f.controller
                        .observeAll()
                        .first()
                        .notices,
                )
                assertEquals(OrphanDeleteResult.Deleted(4096), f.controller.deleteOrphanFiles())
                assertTrue(
                    f.controller
                        .observeAll()
                        .first()
                        .notices
                        .isEmpty(),
                )
            }
        }

    private companion object {
        const val MB = 1024L * 1024
        const val CAP_BYTES = 1024 * MB
    }
}
