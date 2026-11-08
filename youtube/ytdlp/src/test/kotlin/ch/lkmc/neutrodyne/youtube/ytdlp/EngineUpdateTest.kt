// SPDX-License-Identifier: Unlicense
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ch.lkmc.neutrodyne.youtube.ytdlp

import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import ch.lkmc.neutrodyne.core.testing.FakeEngineSettings
import ch.lkmc.neutrodyne.youtube.api.EngineAvailability
import ch.lkmc.neutrodyne.youtube.api.EngineRejectReason
import ch.lkmc.neutrodyne.youtube.api.EngineStatus
import ch.lkmc.neutrodyne.youtube.api.EngineUpdateOutcome
import ch.lkmc.neutrodyne.youtube.api.EngineUpdatePolicy
import ch.lkmc.neutrodyne.youtube.api.PrewarmReason
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.api.YouTubeEngine
import ch.lkmc.neutrodyne.youtube.engine.EngineKeys
import ch.lkmc.neutrodyne.youtube.engine.EngineUpdateHooks
import ch.lkmc.neutrodyne.youtube.engine.EngineUpdateScheduler
import ch.lkmc.neutrodyne.youtube.engine.EngineUpdateTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineUpdateTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun scheduledailyEnqueuesOneConnectedPeriodicRunAndCancellationRemovesIt() {
        val manager = testManager()
        val scheduler = WorkManagerEngineUpdateScheduler(context)
        scheduler.scheduleDaily(true)
        val first =
            manager
                .getWorkInfosForUniqueWork(WorkManagerEngineUpdateScheduler.DAILY_WORK)
                .get()
                .single()
        // UPDATE re-enqueues the unique work rather than stacking duplicates.
        scheduler.scheduleDaily(true)
        assertEquals(
            1,
            manager
                .getWorkInfosForUniqueWork(WorkManagerEngineUpdateScheduler.DAILY_WORK)
                .get()
                .size,
        )
        val spec = manager.workDatabase.workSpecDao().getWorkSpec(first.id.toString())!!
        assertTrue(spec.isPeriodic)
        assertEquals(TimeUnit.HOURS.toMillis(24), spec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(
            EngineUpdateWorker.TRIGGER_SCHEDULED,
            spec.input.getString(EngineUpdateWorker.TRIGGER_KEY),
        )
        scheduler.scheduleDaily(false)
        assertEquals(
            listOf(androidx.work.WorkInfo.State.CANCELLED),
            manager
                .getWorkInfosForUniqueWork(WorkManagerEngineUpdateScheduler.DAILY_WORK)
                .get()
                .map { it.state },
        )
    }

    @Test fun immediateCheckKeepsOneBreakerRunWithConnectedNetwork() {
        val manager = testManager()
        val scheduler = WorkManagerEngineUpdateScheduler(context)
        scheduler.requestImmediateCheck()
        val first =
            manager
                .getWorkInfosForUniqueWork(WorkManagerEngineUpdateScheduler.NOW_WORK)
                .get()
                .single()
        // ExistingWorkPolicy.KEEP: a second breaker opening while a check waits is a no-op.
        scheduler.requestImmediateCheck()
        assertEquals(
            first.id,
            manager
                .getWorkInfosForUniqueWork(WorkManagerEngineUpdateScheduler.NOW_WORK)
                .get()
                .single()
                .id,
        )
        val spec = manager.workDatabase.workSpecDao().getWorkSpec(first.id.toString())!!
        assertFalse(spec.isPeriodic)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(
            EngineUpdateWorker.TRIGGER_BREAKER,
            spec.input.getString(EngineUpdateWorker.TRIGGER_KEY),
        )
    }

    @Test fun workerMapsTriggerDataAndRunsTheHooksOnce() =
        runTest {
            val hooks = RecordingHooks()
            worker(hooks).doWork()
            assertEquals(listOf(EngineUpdateTrigger.SCHEDULED), hooks.triggers)
            worker(hooks, EngineUpdateWorker.TRIGGER_BREAKER).doWork()
            worker(hooks, EngineUpdateWorker.TRIGGER_MANUAL).doWork()
            assertEquals(
                listOf(
                    EngineUpdateTrigger.SCHEDULED,
                    EngineUpdateTrigger.BREAKER,
                    EngineUpdateTrigger.MANUAL,
                ),
                hooks.triggers,
            )
        }

    @Test fun workerRetriesTransientAndStagedOutcomesAndReportsTerminalOnes() =
        runTest {
            val hooks = RecordingHooks()
            hooks.outcome = EngineUpdateOutcome.Failed(TransientKind.NETWORK)
            assertEquals(ListenableWorker.Result.retry(), worker(hooks).doWork())
            hooks.outcome = EngineUpdateOutcome.Staged("2026.11.05")
            assertEquals(ListenableWorker.Result.retry(), worker(hooks).doWork())
            hooks.outcome = EngineUpdateOutcome.UpToDate
            val success = worker(hooks).doWork()
            assertTrue(success is ListenableWorker.Result.Success)
            assertEquals("upToDate", success.outputData.getString(EngineUpdateWorker.OUTCOME_KEY))
            hooks.outcome = EngineUpdateOutcome.Rejected("2026.11.05", EngineRejectReason.SELFTEST_FAILED)
            val rejected = worker(hooks).doWork()
            assertTrue(rejected is ListenableWorker.Result.Success)
            assertEquals(
                "rejected:2026.11.05:SELFTEST_FAILED",
                rejected.outputData.getString(EngineUpdateWorker.OUTCOME_KEY),
            )
        }

    @Test fun workerBoundsRetriesAndCarriesTheLastOutcomeOnFailure() =
        runTest {
            val hooks = RecordingHooks()
            hooks.outcome = EngineUpdateOutcome.Failed(TransientKind.NETWORK)
            val capped =
                TestListenableWorkerBuilder<EngineUpdateWorker>(context)
                    .setWorkerFactory(factory(hooks))
                    .setRunAttemptCount(EngineUpdateWorker.MAX_ATTEMPTS)
                    .build()
            val result = capped.doWork()
            assertTrue(result is ListenableWorker.Result.Failure)
            assertEquals(
                "failed:NETWORK",
                result.outputData.getString(EngineUpdateWorker.OUTCOME_KEY),
            )
        }

    @Test fun cappedRunReportingATerminalOutcomeIsNotAFailure() =
        runTest {
            val hooks = RecordingHooks()
            hooks.outcome = EngineUpdateOutcome.Activated("2026.11.05")
            val capped =
                TestListenableWorkerBuilder<EngineUpdateWorker>(context)
                    .setWorkerFactory(factory(hooks))
                    .setRunAttemptCount(EngineUpdateWorker.MAX_ATTEMPTS)
                    .build()
            val result = capped.doWork()
            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals(
                "activated:2026.11.05",
                result.outputData.getString(EngineUpdateWorker.OUTCOME_KEY),
            )
        }

    @Test fun workerPropagatesCancellationSoTheStoreContractStaysIntact() =
        runTest {
            val hooks = RecordingHooks()
            hooks.onRun = { throw CancellationException("stopped") }
            assertFailsWith<CancellationException> { worker(hooks).doWork() }
        }

    @Test fun initializerSchedulesOnlyWhileEngineIsEligible() =
        runTest {
            val settings = FakeEngineSettings()
            val engine = FakeEngine()
            val scheduler = RecordingScheduler()
            val initializer = EngineUpdateInitializer(settings, engine, scheduler, backgroundScope)
            assertEquals(200, initializer.order)
            initializer.run()
            runCurrent()
            engine.status.value = EngineStatus(EngineAvailability.READY)
            runCurrent()
            assertEquals(listOf(true), scheduler.dailies)
            settings.state.value =
                settings.state.value.copy(updatePolicy = EngineUpdatePolicy.OFF)
            runCurrent()
            assertEquals(listOf(true, false), scheduler.dailies)
            settings.state.value =
                settings.state.value.copy(updatePolicy = EngineUpdatePolicy.APPROVED)
            runCurrent()
            assertEquals(listOf(true, false, true), scheduler.dailies)
            // Availability states that can never serve drop the daily registration.
            for (
            unavailable in
            listOf(
                EngineAvailability.NOT_IN_THIS_APK,
                EngineAvailability.DISABLED,
                EngineAvailability.NOT_YET_AVAILABLE,
            )
            ) {
                engine.status.value = EngineStatus(unavailable)
                runCurrent()
            }
            assertEquals(listOf(true, false, true, false), scheduler.dailies)
            // A failed engine stays scheduled: an approved update may be the fix.
            engine.status.value = EngineStatus(EngineAvailability.FAILED)
            runCurrent()
            assertEquals(listOf(true, false, true, false, true), scheduler.dailies)
            // Idempotent within an unchanged eligibility state.
            engine.status.value = engine.status.value.copy(version = "2026.11.05")
            runCurrent()
            assertEquals(listOf(true, false, true, false, true), scheduler.dailies)
        }

    @Test fun tinkVerifierAcceptsRealSignaturesAndRejectsMalformedInput() {
        val verifier = TinkEd25519Verifier()
        val generator = KeyPairGenerator.getInstance("Ed25519")
        val pair = generator.generateKeyPair()
        val message = "engine-manifest".toByteArray()
        val signature =
            Signature
                .getInstance("Ed25519")
                .apply {
                    initSign(pair.private)
                    update(message)
                }.sign()
        // Ed25519 SPKI is a 12-byte prefix over the raw 32-byte key.
        val rawKey =
            pair.public.encoded
                .takeLast(32)
                .toByteArray()
        assertTrue(verifier.verify(rawKey, message, signature))
        assertFalse(verifier.verify(rawKey, "other".toByteArray(), signature))
        val corrupted = signature.copyOf().also { it[0] = it[0].inc() }
        assertFalse(verifier.verify(rawKey, message, corrupted))
        assertFalse(verifier.verify(ByteArray(31), message, signature))
        assertFalse(verifier.verify(rawKey, message, ByteArray(63)))
        assertFalse(verifier.verify(ByteArray(32), message, signature))
    }

    @Test fun packagedKeysParseWithThePinnedUpstreamFingerprint() {
        val paths = AndroidEngineStorePaths(context)
        val keys = EngineKeys.parse(paths.manifestKeysText(), paths.upstreamKeyText())
        assertEquals(1, keys.manifestEd25519.size)
        assertEquals(32, keys.manifestEd25519.single().size)
        assertTrue(keys.upstream.fingerprint.equals(EngineKeys.UPSTREAM_FINGERPRINT, ignoreCase = true))
    }

    private fun testManager(): WorkManagerImpl {
        val config =
            Configuration
                .Builder()
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        return WorkManagerImpl.getInstance(context)
    }

    private fun factory(hooks: RecordingHooks): WorkerFactory =
        object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = EngineUpdateWorker(appContext, workerParameters, hooks)
        }

    private fun worker(
        hooks: RecordingHooks,
        trigger: String? = null,
    ): EngineUpdateWorker {
        val builder =
            TestListenableWorkerBuilder<EngineUpdateWorker>(context)
                .setWorkerFactory(factory(hooks))
        if (trigger != null) builder.setInputData(workDataOf(EngineUpdateWorker.TRIGGER_KEY to trigger))
        return builder.build()
    }

    private class RecordingHooks : EngineUpdateHooks {
        val triggers = mutableListOf<EngineUpdateTrigger>()
        var outcome: EngineUpdateOutcome = EngineUpdateOutcome.UpToDate
        var onRun: (suspend () -> EngineUpdateOutcome)? = null

        override suspend fun runUpdate(trigger: EngineUpdateTrigger): EngineUpdateOutcome {
            triggers += trigger
            return onRun?.invoke() ?: outcome
        }

        override suspend fun resetToBundled() = Unit

        override fun requestImmediateCheck() = Unit
    }

    private class RecordingScheduler : EngineUpdateScheduler {
        val dailies = mutableListOf<Boolean>()
        var immediates = 0

        override fun scheduleDaily(enabled: Boolean) {
            dailies += enabled
        }

        override fun requestImmediateCheck() {
            immediates++
        }
    }

    private class FakeEngine : YouTubeEngine {
        override val status = MutableStateFlow(EngineStatus(EngineAvailability.STOPPED))

        override fun prewarm(reason: PrewarmReason) = Unit

        override fun retryStart() = Unit

        override suspend fun checkForUpdate(): EngineUpdateOutcome = EngineUpdateOutcome.UpToDate

        override suspend fun resetToBundled() = Unit
    }
}
