// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import android.app.Application
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.WorkerCreator
import ch.lkmc.neutrodyne.core.common.WorkerKey
import ch.lkmc.neutrodyne.core.domain.update.AppUpdateChecker
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckState
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class WorkUpdateCheckRunner(
    private val application: Application,
    private val checker: Provider<AppUpdateChecker>,
) : UpdateCheckRunner {
    private val workManager get() = WorkManager.getInstance(application)

    override suspend fun setAutomaticChecks(mode: UpdateScheduling) {
        if (mode == UpdateScheduling.DISABLED) {
            workManager.cancelUniqueWork(UPDATE_PERIODIC_WORK).await()
            return
        }

        val request =
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(PERIOD_HOURS, TimeUnit.HOURS, FLEX_HOURS, TimeUnit.HOURS)
                .setInitialDelay(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_HOURS, TimeUnit.HOURS)
                .build()
        // UPDATE keeps the original enqueue time across process starts; flex supplies OS jitter.
        workManager.enqueueUniquePeriodicWork(UPDATE_PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request).await()
    }

    override suspend fun runNow(): UpdateCheckState {
        val request =
            OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                .setInputData(workDataOf(UpdateCheckWorker.MANUAL_INPUT to true))
                .build()
        try {
            workManager.enqueueUniqueWork(UPDATE_MANUAL_WORK, ExistingWorkPolicy.REPLACE, request).await()
            workManager.getWorkInfoByIdFlow(request.id).filterNotNull().first { it.state.isFinished }
            // A periodic worker can start between manual completion and this observation.
            return checker().state.first { it != UpdateCheckState.Checking }
        } catch (cancelled: CancellationException) {
            // Cancel this request, not a later replacement under the same unique name.
            withContext(NonCancellable) { workManager.cancelWorkById(request.id).await() }
            throw cancelled
        }
    }

    override suspend fun cancelManual() {
        workManager.cancelUniqueWork(UPDATE_MANUAL_WORK).await()
    }

    private companion object {
        const val PERIOD_HOURS = 24L
        const val FLEX_HOURS = 6L
        const val BACKOFF_HOURS = 1L
    }
}

@BindingContainer
@ContributesTo(AppScope::class)
object UpdateWorkerBindings {
    @Provides
    @IntoMap
    @WorkerKey(UpdateCheckWorker::class)
    fun worker(factory: UpdateCheckWorker.Factory): WorkerCreator =
        { context, params -> factory.create(context, params) }
}
