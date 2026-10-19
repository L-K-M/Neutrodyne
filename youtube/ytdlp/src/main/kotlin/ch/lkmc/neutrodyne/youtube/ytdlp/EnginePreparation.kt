// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlp

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.youtube.engine.EnginePlatformPreflight
import ch.lkmc.neutrodyne.youtube.engine.EnginePreparation
import ch.lkmc.neutrodyne.youtube.engine.EngineSettings
import ch.lkmc.neutrodyne.youtube.engine.EngineStore
import ch.lkmc.neutrodyne.youtube.engine.YtxDeadline
import ch.lkmc.neutrodyne.youtube.engine.YtxLimits
import ch.lkmc.neutrodyne.youtube.engine.YtxMethod
import ch.lkmc.neutrodyne.youtube.engine.YtxTransport
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** W11 contributes these hooks and the WorkerCreator only when activation is approved. Spike pending. */
class EngineStatusInitializer(
    private val store: EngineStore,
) : AppInitializer {
    override val order = 150

    override suspend fun run() = store.loadStatus()
}

class EnginePrepareInitializer(
    private val store: EngineStore,
    private val workManager: WorkManager,
) : AppInitializer {
    override val order = 210

    override suspend fun run() {
        if (store.preparation.value == EnginePreparation.COMPILED) return
        workManager.enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<EnginePrepareWorker>()
                .setInitialDelay(
                    INITIAL_DELAY_SECONDS,
                    TimeUnit.SECONDS,
                ).build(),
        )
    }

    companion object {
        const val WORK_NAME = "engine-prepare"
        private const val INITIAL_DELAY_SECONDS = 30L
    }
}

/** W11's main-process worker factory supplies the store and W4 transport. No Python on this thread. */
class EnginePrepareWorker(
    context: Context,
    parameters: WorkerParameters,
    private val store: EngineStore,
    private val transport: YtxTransport,
    private val settings: EngineSettings,
    private val preflight: EnginePlatformPreflight,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return try {
            settings.awaitLoaded()
            if (!settings.state.value.enabled || preflight.limitation() != null) {
                return Result.success(workDataOf("preparation" to "skipped-ineligible"))
            }
            store.prepareBundled()
            // The ping waits for W4's worker-owned compile/import and final marker. Values provisional.
            transport.call(
                YtxMethod.PING,
                "{}",
                YtxDeadline(SystemClock.elapsedRealtime() + YtxLimits.COMPILE_MS + YtxLimits.START_MS),
            )
            store.loadStatus()
            if (store.preparation.value == EnginePreparation.COMPILED) Result.success() else Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
