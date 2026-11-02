// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import ch.lkmc.neutrodyne.core.datastore.SettingStore
import ch.lkmc.neutrodyne.core.domain.DownloadMover
import ch.lkmc.neutrodyne.core.domain.MoveResult
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import ch.lkmc.neutrodyne.core.testing.FakeDownloadMover
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 33, 34, 36])
class DownloadMoveWorkerHostTest {
    private val context = RuntimeEnvironment.getApplication()
    private val store = MoveHostStore()
    private val mover = FakeDownloadMover()
    private val clock = TestClock(elapsedMs = 123)
    private val continued = mutableListOf<String>()

    @Test fun eightMinuteDeadlineAndContinuationUseSameTarget() =
        runTest {
            store.set(DownloadSettingKeys.MOVE_TARGET, "new")
            mover.result = MoveResult.DEADLINE
            assertEquals(ListenableWorker.Result.success(), worker().doWork())
            assertEquals(listOf<Pair<String, Long?>>("new" to 480_123L), mover.calls)
            assertEquals(listOf("new"), continued)
            val request = DownloadMoveWorker.request("new")
            assertEquals("download-move", DownloadMoveWorker.NAME)
            assertEquals("new", request.workSpec.input.getString(DownloadMoveWorker.KEY_TARGET))
            assertTrue(request.workSpec.constraints.requiresStorageNotLow())
            assertFalse(request.workSpec.expedited)
            assertEquals(0L, request.workSpec.initialDelay)
        }

    @Test fun pendingStorageAndCompleteNeverAppendHotContinuation() =
        runTest {
            store.set(DownloadSettingKeys.MOVE_TARGET, "new")
            for (result in listOf(MoveResult.PENDING, MoveResult.STORAGE_FULL, MoveResult.COMPLETE)) {
                mover.result = result
                assertEquals(ListenableWorker.Result.success(), worker().doWork())
            }
            assertTrue(continued.isEmpty())
        }

    @Test fun staleInputAndLeaveInPlaceDoNotCallMover() =
        runTest {
            store.set(DownloadSettingKeys.MOVE_TARGET, "other")
            assertEquals(ListenableWorker.Result.success(), worker().doWork())
            store.set(DownloadSettingKeys.MOVE_TARGET, "")
            assertEquals(ListenableWorker.Result.success(), worker().doWork())
            assertTrue(mover.calls.isEmpty())
            assertEquals(ListenableWorker.Result.failure(), worker("").doWork())
        }

    @Test fun cancellationPropagatesAndFailuresRetryWithIntentIntact() =
        runTest {
            store.set(DownloadSettingKeys.MOVE_TARGET, "new")
            val cancelled =
                object : DownloadMover {
                    override suspend fun run(
                        target: String,
                        deadline: Long?,
                    ): MoveResult = throw CancellationException("quit")
                }
            assertFailsWith<CancellationException> { worker(owner = cancelled).doWork() }
            val failure =
                object : DownloadMover {
                    override suspend fun run(
                        target: String,
                        deadline: Long?,
                    ): MoveResult = throw java.io.IOException("write failed")
                }
            assertEquals(ListenableWorker.Result.retry(), worker(owner = failure).doWork())
            assertEquals("new", store.get(DownloadSettingKeys.MOVE_TARGET))
        }

    private fun worker(
        target: String = "new",
        owner: DownloadMover = mover,
    ): DownloadMoveWorker =
        TestListenableWorkerBuilder<DownloadMoveWorker>(context)
            .setInputData(workDataOf(DownloadMoveWorker.KEY_TARGET to target))
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker =
                        DownloadMoveWorker(appContext, workerParameters, owner, store, clock) { continued += it }
                },
            ).build()
}

private class MoveHostStore : SettingStore {
    private val delegate = FakeSettingsRepository()
    override val file = SettingsFile.DEVICE

    override fun <T : Any> observe(key: SettingKey<T>) = delegate.observe(key)

    override suspend fun <T : Any> get(key: SettingKey<T>) = delegate.get(key)

    override suspend fun <T : Any> set(
        key: SettingKey<T>,
        value: T,
    ) {
        delegate.set(key, value)
    }

    override suspend fun <T : Any> update(
        key: SettingKey<T>,
        transform: (T) -> T,
    ) {
        delegate.set(key, transform(delegate.get(key)))
    }

    override suspend fun reset(key: SettingKey<*>) = delegate.reset(key)
}
