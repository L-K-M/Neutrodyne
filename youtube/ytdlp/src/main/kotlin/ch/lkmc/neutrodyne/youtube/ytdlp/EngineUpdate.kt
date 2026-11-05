// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlp

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.youtube.api.EngineAvailability
import ch.lkmc.neutrodyne.youtube.api.EngineUpdateOutcome
import ch.lkmc.neutrodyne.youtube.api.EngineUpdatePolicy
import ch.lkmc.neutrodyne.youtube.api.YouTubeEngine
import ch.lkmc.neutrodyne.youtube.engine.Ed25519Verifier
import ch.lkmc.neutrodyne.youtube.engine.EngineSettings
import ch.lkmc.neutrodyne.youtube.engine.EngineUpdateHooks
import ch.lkmc.neutrodyne.youtube.engine.EngineUpdateOutcomes
import ch.lkmc.neutrodyne.youtube.engine.EngineUpdateScheduler
import ch.lkmc.neutrodyne.youtube.engine.EngineUpdateTrigger
import com.google.crypto.tink.subtle.Ed25519Verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.security.GeneralSecurityException
import java.util.concurrent.TimeUnit

/**
 * 04 Update flow, Android runner: `engine-update` is the daily periodic run; `engine-update-now`
 * is the one-time keep-run after a breaker opening. `YouTubeEngine.checkForUpdate()` runs the
 * in-process `EngineUpdater` directly — the caller awaits its outcome, so it needs no work (see
 * the 2026-11-05 implementation note in 04-youtube.md).
 */
class WorkManagerEngineUpdateScheduler(
    private val workManager: WorkManager,
) : EngineUpdateScheduler {
    override fun scheduleDaily(enabled: Boolean) {
        if (!enabled) {
            workManager.cancelUniqueWork(DAILY_WORK)
            return
        }
        workManager.enqueueUniquePeriodicWork(
            DAILY_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<EngineUpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(CONNECTED)
                .setInputData(workDataOf(EngineUpdateWorker.TRIGGER_KEY to EngineUpdateWorker.TRIGGER_SCHEDULED))
                .build(),
        )
    }

    override fun requestImmediateCheck() {
        workManager.enqueueUniqueWork(
            NOW_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<EngineUpdateWorker>()
                .setConstraints(CONNECTED)
                .setInputData(workDataOf(EngineUpdateWorker.TRIGGER_KEY to EngineUpdateWorker.TRIGGER_BREAKER))
                .build(),
        )
    }

    private companion object {
        const val DAILY_WORK = "engine-update"
        const val NOW_WORK = "engine-update-now"
        val CONNECTED = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    }
}

/** The worker only drives the in-process updater; trust, staging and rollback live in `:youtube:engine`. */
class EngineUpdateWorker(
    context: Context,
    parameters: WorkerParameters,
    private val hooks: EngineUpdateHooks,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val trigger =
            when (inputData.getString(TRIGGER_KEY)) {
                TRIGGER_BREAKER -> EngineUpdateTrigger.BREAKER
                TRIGGER_MANUAL -> EngineUpdateTrigger.MANUAL
                else -> EngineUpdateTrigger.SCHEDULED
            }
        return when (val outcome = hooks.runUpdate(trigger)) {
            is EngineUpdateOutcome.Failed -> Result.retry()
            is EngineUpdateOutcome.Staged -> Result.retry()
            else -> Result.success(workDataOf("outcome" to EngineUpdateOutcomes.encode(outcome)))
        }
    }

    companion object {
        const val TRIGGER_KEY = "trigger"
        const val TRIGGER_SCHEDULED = "scheduled"
        const val TRIGGER_BREAKER = "breaker"
        const val TRIGGER_MANUAL = "manual"
    }
}

/**
 * The order-200 initializer of the doc table: keeps `engine-update` aligned with the portable
 * policy while the engine is usable (never `NOT_IN_THIS_APK`/`DISABLED`/`NOT_YET_AVAILABLE`).
 */
class EngineUpdateInitializer(
    private val settings: EngineSettings,
    private val engine: YouTubeEngine,
    private val scheduler: EngineUpdateScheduler,
    private val scope: CoroutineScope,
) : AppInitializer {
    override val order = 200

    override suspend fun run() {
        scope.launch {
            settings.awaitLoaded()
            combine(settings.state, engine.status) { snapshot, status ->
                snapshot.updatePolicy != EngineUpdatePolicy.OFF &&
                    status.availability !in INELIGIBLE
            }.distinctUntilChanged().collect { eligible ->
                scheduler.scheduleDaily(eligible)
            }
        }
    }

    private companion object {
        val INELIGIBLE =
            setOf(
                EngineAvailability.NOT_IN_THIS_APK,
                EngineAvailability.DISABLED,
                EngineAvailability.NOT_YET_AVAILABLE,
            )
    }
}

/** Tink's Ed25519 verifier for Android < 33, where `Signature("Ed25519")` is unavailable. */
class TinkEd25519Verifier : Ed25519Verifier {
    override fun verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        return try {
            Ed25519Verify(publicKey).verify(signature, message)
            true
        } catch (invalid: GeneralSecurityException) {
            false
        }
    }
}
