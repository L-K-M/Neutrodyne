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
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.youtube.engine.EnginePlatformPreflight
import ch.lkmc.neutrodyne.youtube.engine.EnginePreparation
import ch.lkmc.neutrodyne.youtube.engine.EngineSettings
import ch.lkmc.neutrodyne.youtube.engine.EngineStore
import ch.lkmc.neutrodyne.youtube.engine.YtxCallException
import ch.lkmc.neutrodyne.youtube.engine.YtxControlRequest
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
    private val pingRequest: YtxControlRequest,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        var step = PrepareStep.SETTINGS
        return try {
            settings.awaitLoaded()
            if (!settings.state.value.enabled || preflight.limitation() != null) {
                return Result.success(workDataOf("preparation" to "skipped-ineligible"))
            }
            step = PrepareStep.EXTRACT
            store.prepareBundled()
            // The ping waits for W4's worker-owned compile/import and final marker. Values provisional.
            step = PrepareStep.COMPILE
            transport.call(
                YtxMethod.PING,
                pingRequest.toJson(),
                YtxDeadline(SystemClock.elapsedRealtime() + YtxLimits.COMPILE_MS + YtxLimits.START_MS),
            )
            step = PrepareStep.STATUS
            store.loadStatus()
            if (store.preparation.value == EnginePreparation.COMPILED) return Result.success()
            Log.w(TAG) { "Engine preparation found no compiled engine after the ping; retrying" }
            Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A failed extraction makes the engine FAILED ("couldn't start") with nothing else
            // logged; the in-app Diagnostics log is the only place a phone shows it. A failed
            // compile was already logged by the transport with its step and cause.
            val cause = (failure as? YtxCallException)?.code ?: failure::class.simpleName
            Log.w(TAG, failure.takeUnless { it is YtxCallException }) {
                "Engine preparation failed at $step: $cause; retrying"
            }
            Result.retry()
        }
    }

    /** Where a preparation run stopped; logged with its failure. */
    private enum class PrepareStep { SETTINGS, EXTRACT, COMPILE, STATUS }

    private companion object {
        const val TAG = "EnginePrepareWorker"
    }
}
