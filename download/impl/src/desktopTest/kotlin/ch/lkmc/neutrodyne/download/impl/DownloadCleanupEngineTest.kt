// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.domain.CleanupReason
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.WaitReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DownloadCleanupEngineTest {
    @Test fun cleanupRunsBeforeCommittingAStorageWaitAndProtectsTheActiveOwner() =
        runTest {
            EngineRig(this).use { f ->
                val id = f.seed().single()
                f.source.beforePrepare = { _, _ ->
                    Prepared.Wait(
                        WaitReason.STORAGE,
                        DownloadError.STORAGE_FULL,
                        f.clock.now() + WAIT_MS,
                        countsAsAttempt = false,
                    )
                }
                f.cleanup.onRun = {
                    val active = f.row(id)
                    assertNotNull(active.runnerToken)
                    assertEquals(DownloadState.RESOLVING, active.state)
                    assertEquals(WaitReason.NONE, active.waitReason)
                }
                f.engine.drain(drainRequest())
                assertEquals(listOf(CleanupReason.STORAGE), f.cleanup.calls)
                assertEquals(DownloadState.QUEUED, f.row(id).state)
                assertEquals(WaitReason.STORAGE, f.row(id).waitReason)
                assertEquals(0, f.row(id).attempt)
            }
        }

    @Test fun cleanupIoFailureRetainsTheStorageWaitAndRetry() =
        runTest {
            EngineRig(this).use { f ->
                val id = f.seed().single()
                val retryAt = f.clock.now() + WAIT_MS
                f.source.beforePrepare = { _, _ ->
                    Prepared.Wait(
                        WaitReason.STORAGE,
                        DownloadError.STORAGE_UNAVAILABLE,
                        retryAt,
                        countsAsAttempt = false,
                    )
                }
                f.cleanup.onRun = { throw IOException("Root vanished during cleanup") }
                f.engine.drain(drainRequest())
                assertEquals(listOf(CleanupReason.STORAGE), f.cleanup.calls)
                assertEquals(DownloadState.QUEUED, f.row(id).state)
                assertEquals(DownloadError.STORAGE_UNAVAILABLE, f.row(id).lastError)
                assertEquals(retryAt, f.row(id).nextAttemptAt)
            }
        }

    @Test fun pausingATransferCancelsItsPendingCleanupWithoutDeadlockingClaims() =
        runTest {
            EngineRig(this).use { f ->
                val id = f.seed().single()
                val entered = CompletableDeferred<Unit>()
                f.source.beforePrepare = { _, _ ->
                    Prepared.Wait(
                        WaitReason.STORAGE,
                        DownloadError.STORAGE_FULL,
                        f.clock.now() + WAIT_MS,
                        countsAsAttempt = false,
                    )
                }
                f.cleanup.onRun = {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                val runner = async { f.engine.drain(drainRequest()) }
                entered.await()
                f.controller.pause(listOf(id))
                runner.await()
                assertEquals(DownloadState.PAUSED, f.row(id).state)
                assertEquals(DownloadStopReasons.USER_PAUSE, f.row(id).lastStopReason)
            }
        }

    private companion object {
        const val WAIT_MS = 30L * 60 * 1000
    }
}
