// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.update

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.update.UpdateRuntimeTarget
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ch.lkmc.neutrodyne.core.model.InstallKind as Kind
import ch.lkmc.neutrodyne.core.model.settings.UpdateSettingKeys as Keys
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckError as CheckError
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckState as CheckState
import ch.lkmc.neutrodyne.core.model.update.UpdateDisabledReason as DisabledReason

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateCheckerTest {
    private class Harness(
        val test: TestScope,
        val build: BuildInfo = updateBuild(),
        val settings: FakeSettingsRepository = FakeSettingsRepository(),
        val store: MemoryUpdateStore = MemoryUpdateStore(),
        target: UpdateRuntimeTarget = UpdateRuntimeTarget.Android("arm64-v8a", 37),
        jitter: Long = 0,
        appScope: CoroutineScope = test.backgroundScope,
    ) {
        val source = TestUpdateSource()
        val runner = TestUpdateRunner()
        val notifications = TestUpdateNotifications()
        val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
        val clock = TestClock.from(test.testScheduler)
        var jitterCalls = 0
        val checker =
            AppUpdateCheckerImpl(
                build,
                runtime(target),
                settings,
                source,
                store,
                DesktopAssetSelector(),
                notifications,
                { runner },
                network,
                clock,
                UpdateJitter {
                    jitterCalls++
                    jitter
                },
                appScope,
            )

        init {
            runner.executor = checker
        }

        suspend fun due() {
            checker.initialize()
            test.advanceTimeBy((store.value!!.nextDueAtMs - clock.now()).coerceAtLeast(0))
            test.runCurrent()
        }

        suspend fun scheduled(): CheckState {
            due()
            return checker.execute(UpdateInvocation.SCHEDULED)
        }
    }

    @Test fun startupLoadsStateAndAnchorWithoutNetwork() =
        runTest {
            val h = Harness(this)
            h.checker.initialize()
            assertEquals(CheckState.Idle(null), h.checker.state.value)
            assertEquals(0, h.source.calls)
            assertEquals(h.clock.now() + DAY, h.store.value?.nextDueAtMs)
            assertEquals(listOf(UpdateScheduling.ENABLED), h.runner.scheduling)
        }

    @Test fun initializationIsIdempotentAndDoesNotRerollInitialDelay() =
        runTest {
            val h = Harness(this, build = updateBuild(platform = BuildInfo.Platform.DESKTOP), jitter = HOUR)
            h.checker.initialize()
            val saved = h.store.value
            advanceTimeBy(HOUR)
            h.checker.initialize()
            assertEquals(saved, h.store.value)
            assertEquals(1, h.store.writes)
            assertEquals(0, h.jitterCalls)
        }

    @Test fun restartPreservesFirstStartAnchorAndRetryDueTime() =
        runTest {
            val first = Harness(this)
            first.checker.initialize()
            advanceTimeBy(HOUR)
            val second = Harness(this, store = first.store)
            second.checker.initialize()
            assertEquals(TestClock.DEFAULT_NOW + DAY, second.store.value?.nextDueAtMs)
            assertEquals(0, second.source.calls)
            first.source.result = Outcome.Failure(CheckError.NETWORK)
            first.scheduled()
            val retry = first.store.value
            val third = Harness(this, store = first.store)
            third.checker.initialize()
            assertEquals(retry, third.store.value)
        }

    @Test fun scheduledCheckIsInertUntilDueThenUsesJitterOnce() =
        runTest {
            val h = Harness(this, build = updateBuild(platform = BuildInfo.Platform.DESKTOP), jitter = HOUR)
            h.checker.initialize()
            h.checker.execute(UpdateInvocation.SCHEDULED)
            assertEquals(0, h.source.calls)
            h.scheduled()
            assertEquals(1, h.source.calls)
            assertEquals(h.clock.now() + DAY + HOUR, h.store.value?.nextDueAtMs)
            h.checker.execute(UpdateInvocation.SCHEDULED)
            assertEquals(1, h.jitterCalls)
            assertEquals(1, h.source.calls)
        }

    @Test fun desktopRetriesAtOneTwoFourEightSixteenAndTwentyFourHours() =
        runTest {
            val h =
                Harness(
                    this,
                    build = updateBuild(platform = BuildInfo.Platform.DESKTOP),
                    target = UpdateRuntimeTarget.Desktop("6.1"),
                )
            h.source.result = Outcome.Failure(CheckError.RATE_LIMITED)
            listOf(1L, 2, 4, 8, 16, 24, 24).forEach { hours ->
                h.scheduled()
                assertEquals(h.clock.now() + hours * HOUR, h.store.value?.nextDueAtMs)
            }
            assertEquals(7, h.source.calls)
            assertEquals(6, h.store.value?.retryAttempt)
        }

    @Test fun successfulCheckResetsFailureBackoff() =
        runTest {
            val h = Harness(this)
            h.source.result = Outcome.Failure(CheckError.NETWORK)
            h.scheduled()
            h.source.result = Outcome.Success(manifest())
            h.scheduled()
            assertEquals(0, h.store.value?.retryAttempt)
            assertEquals(h.clock.now() + DAY, h.store.value?.nextDueAtMs)
        }

    @Test fun checksOffDisableSchedulingAndScheduledExecution() =
        runTest {
            val settings = FakeSettingsRepository().also { it.set(Keys.CHECK_ENABLED, false) }
            val h = Harness(this, settings = settings)
            h.checker.initialize()
            advanceTimeBy(2 * DAY)
            assertEquals(
                CheckState.Disabled(DisabledReason.CHECKS_OFF),
                h.checker.execute(UpdateInvocation.SCHEDULED),
            )
            assertEquals(listOf(UpdateScheduling.DISABLED), h.runner.scheduling)
            assertEquals(0, h.source.calls)
        }

    @Test fun manualWhileOffReturnsAvailableAndPersistsUntilSwitchOrRestart() =
        runTest {
            val settings = FakeSettingsRepository().also { it.set(Keys.CHECK_ENABLED, false) }
            val h = Harness(this, settings = settings)
            assertIs<CheckState.Available>(h.checker.checkNow())
            assertEquals(1, h.runner.enqueued)
            assertEquals(1, h.source.calls)
            assertEquals(0, h.notifications.posted.size)
            assertEquals(1000095L, settings.get(Keys.NOTIFIED_VERSION_CODE))
            val restarted = Harness(this, settings = settings, store = h.store)
            restarted.checker.initialize()
            assertEquals(CheckState.Disabled(DisabledReason.CHECKS_OFF), restarted.checker.state.value)
        }

    @Test fun debugWinsOverRestoredTrueAndRefusesManualAndScheduledWork() =
        runTest {
            val settings = FakeSettingsRepository().also { it.set(Keys.CHECK_ENABLED, true) }
            val h = Harness(this, build = updateBuild(debug = true), settings = settings)
            assertEquals(CheckState.Disabled(DisabledReason.DEV_BUILD), h.checker.checkNow())
            advanceTimeBy(2 * DAY)
            h.checker.execute(UpdateInvocation.SCHEDULED)
            assertEquals(0, h.source.calls)
            assertEquals(0, h.runner.enqueued)
            assertEquals(listOf(UpdateScheduling.DISABLED), h.runner.scheduling)
        }

    @Test fun desktopDevIdentityRefusesEvenWhenDebugFlagIsWrong() =
        runTest {
            val h = Harness(this, build = updateBuild(platform = BuildInfo.Platform.DESKTOP, kind = Kind.DEV))
            assertEquals(CheckState.Disabled(DisabledReason.DEV_BUILD), h.checker.checkNow())
            assertEquals(0, h.source.calls)
        }

    @Test fun manualThrottleIncludesFailuresAndBoundaryIsSixtySeconds() =
        runTest {
            val h = Harness(this)
            h.source.result = Outcome.Failure(CheckError.RATE_LIMITED)
            assertEquals(CheckError.RATE_LIMITED, assertIs<CheckState.Failed>(h.checker.checkNow()).error)
            advanceTimeBy(59_999)
            h.checker.checkNow()
            assertEquals(1, h.source.calls)
            assertEquals(1, h.runner.enqueued)
            advanceTimeBy(1)
            h.checker.checkNow()
            assertEquals(2, h.source.calls)
        }

    @Test fun offlineManualFailsWithoutEnqueueOrChecking() =
        runTest {
            val h = Harness(this)
            h.network.setStatus(FakeNetworkMonitor.OFFLINE)
            assertEquals(CheckError.NETWORK, assertIs<CheckState.Failed>(h.checker.checkNow()).error)
            assertEquals(0, h.runner.enqueued)
            assertEquals(0, h.source.calls)
            h.network.setStatus(FakeNetworkMonitor.ONLINE)
            assertIs<CheckState.Available>(h.checker.checkNow())
        }

    @Test fun offlineRecheckRetainsAvailableWithLastError() =
        runTest {
            val h = Harness(this)
            val original = assertIs<CheckState.Available>(h.checker.checkNow())
            advanceTimeBy(60_000)
            h.network.setStatus(FakeNetworkMonitor.OFFLINE)
            assertEquals(original.copy(lastError = CheckError.NETWORK), h.checker.checkNow())
            assertEquals(1, h.runner.enqueued)
        }

    @Test fun networkFailureRecheckRetainsAvailableWithLastError() =
        runTest {
            val h = Harness(this)
            val original = assertIs<CheckState.Available>(h.checker.checkNow())
            advanceTimeBy(60_000)
            h.source.result = Outcome.Failure(CheckError.MANIFEST_INVALID)
            assertEquals(original.copy(lastError = CheckError.MANIFEST_INVALID), h.checker.checkNow())
        }

    @Test fun hungSourceCancelsAtThirtySecondsAndLeavesNoCheckingState() =
        runTest {
            val h = Harness(this)
            var cancelled = false
            h.source.action = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            val request = async { h.checker.checkNow() }
            runCurrent()
            assertEquals(CheckState.Checking, h.checker.state.value)
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(CheckError.NETWORK, assertIs<CheckState.Failed>(request.await()).error)
            assertTrue(cancelled)
            assertEquals(1, h.source.calls)
        }

    @Test fun hungRunnerAlsoHasThirtySecondCeiling() =
        runTest {
            val h = Harness(this)
            h.runner.action = { awaitCancellation() }
            val request = async { h.checker.checkNow() }
            runCurrent()
            assertEquals(CheckState.Checking, h.checker.state.value)
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(CheckError.NETWORK, assertIs<CheckState.Failed>(request.await()).error)
            assertEquals(1, h.runner.cancelled)
            assertEquals(0, h.source.calls)
        }

    @Test fun cancellationWhileRunnerIsQueuedRestoresKnownAvailable() =
        runTest {
            val h = Harness(this)
            val available = assertIs<CheckState.Available>(h.checker.checkNow())
            advanceTimeBy(60_000)
            h.runner.action = { awaitCancellation() }
            val request = launch { h.checker.checkNow() }
            runCurrent()
            assertEquals(CheckState.Checking, h.checker.state.value)
            request.cancelAndJoin()
            runCurrent()
            assertEquals(available.copy(lastError = CheckError.NETWORK), h.checker.state.value)
        }

    @Test fun queuedManualRequestSuppressesScheduledDispatchBeforeWorkerStarts() =
        runTest {
            val h = Harness(this)
            h.due()
            val gate = CompletableDeferred<Unit>()
            h.runner.action = {
                gate.await()
                h.checker.execute(UpdateInvocation.MANUAL)
            }
            val manual = async { h.checker.checkNow() }
            runCurrent()
            val scheduled = h.checker.execute(UpdateInvocation.SCHEDULED)
            gate.complete(Unit)
            assertEquals(scheduled, manual.await())
            assertEquals(1, h.source.calls)
            assertEquals(0, h.notifications.posted.size)
        }

    @Test fun callerCancellationCancelsManualWorkAndRestoresState() =
        runTest {
            val h = Harness(this)
            var cancelled = false
            h.source.action = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            val request = launch { h.checker.checkNow() }
            runCurrent()
            request.cancelAndJoin()
            runCurrent()
            assertTrue(cancelled)
            assertIs<CheckState.Failed>(h.checker.state.value)
            assertEquals(1, h.runner.cancelled)
        }

    @Test fun simultaneousManualCallsEnqueueAndFetchOnce() =
        runTest {
            val h = Harness(this)
            val gate = CompletableDeferred<Unit>()
            h.source.action = {
                gate.await()
                Outcome.Success(manifest())
            }
            val first = async { h.checker.checkNow() }
            val second = async { h.checker.checkNow() }
            runCurrent()
            assertEquals(1, h.source.calls)
            assertEquals(1, h.runner.enqueued)
            gate.complete(Unit)
            assertEquals(first.await(), second.await())
            assertEquals(0, h.notifications.posted.size)
        }

    @Test fun manualJoiningScheduledFetchSuppressesNotification() =
        runTest {
            val h = Harness(this)
            h.due()
            val gate = CompletableDeferred<Unit>()
            h.source.action = {
                gate.await()
                Outcome.Success(manifest())
            }
            val scheduled = async { h.checker.execute(UpdateInvocation.SCHEDULED) }
            runCurrent()
            val manual = async { h.checker.checkNow() }
            runCurrent()
            gate.complete(Unit)
            assertEquals(scheduled.await(), manual.await())
            assertEquals(1, h.source.calls)
            assertEquals(0, h.notifications.posted.size)
            assertEquals(1000095L, h.settings.get(Keys.NOTIFIED_VERSION_CODE))
        }

    @Test fun scheduledJoiningManualFetchDoesNotDuplicateIt() =
        runTest {
            val h = Harness(this)
            h.due()
            val gate = CompletableDeferred<Unit>()
            h.source.action = {
                gate.await()
                Outcome.Success(manifest())
            }
            val manual = async { h.checker.checkNow() }
            runCurrent()
            val scheduled = async { h.checker.execute(UpdateInvocation.SCHEDULED) }
            runCurrent()
            gate.complete(Unit)
            assertEquals(manual.await(), scheduled.await())
            assertEquals(1, h.source.calls)
            assertEquals(0, h.notifications.posted.size)
        }

    @Test fun switchOffMidFlightCannotPublishOrNotify() =
        runTest {
            val h = Harness(this)
            h.due()
            h.source.action = { awaitCancellation() }
            val job = launch { h.checker.execute(UpdateInvocation.SCHEDULED) }
            runCurrent()
            h.settings.set(Keys.CHECK_ENABLED, false)
            runCurrent()
            job.join()
            assertEquals(CheckState.Disabled(DisabledReason.CHECKS_OFF), h.checker.state.value)
            assertEquals(0, h.notifications.posted.size)
            assertNull(h.store.value?.manifest)
            assertEquals(UpdateScheduling.DISABLED, h.runner.scheduling.last())
        }

    @Test fun switchOffWhileNotificationDispatchSuspendsCancelsPublication() =
        runTest {
            val h = Harness(this)
            h.due()
            h.notifications.beforePost = {
                h.settings.set(Keys.CHECK_ENABLED, false)
                awaitCancellation()
            }
            val scheduled = launch { h.checker.execute(UpdateInvocation.SCHEDULED) }
            runCurrent()
            scheduled.join()
            assertEquals(CheckState.Disabled(DisabledReason.CHECKS_OFF), h.checker.state.value)
            assertEquals(0, h.notifications.posted.size)
            assertEquals(Keys.ABSENT, h.settings.get(Keys.NOTIFIED_VERSION_CODE))
        }

    @Test fun switchOffAndOnRecomputeWithoutFetching() =
        runTest {
            val h = Harness(this)
            h.checker.checkNow()
            h.settings.set(Keys.CHECK_ENABLED, false)
            runCurrent()
            assertEquals(CheckState.Disabled(DisabledReason.CHECKS_OFF), h.checker.state.value)
            h.settings.set(Keys.CHECK_ENABLED, true)
            runCurrent()
            assertIs<CheckState.Available>(h.checker.state.value)
            assertEquals(1, h.source.calls)
        }

    @Test fun skipIsDurableCancelsNotificationAndHigherVersionSupersedesIt() =
        runTest {
            val h = Harness(this)
            val available = assertIs<CheckState.Available>(h.checker.checkNow())
            h.checker.skip(available.info.versionCode)
            runCurrent()
            assertIs<CheckState.Idle>(h.checker.state.value)
            assertEquals(available.info.versionCode, h.settings.get(Keys.SKIPPED_VERSION_CODE))
            assertEquals(1, h.notifications.cancelled)
            advanceTimeBy(60_000)
            assertIs<CheckState.Idle>(h.checker.checkNow())
            h.source.result = Outcome.Success(manifest("1.0.1"))
            advanceTimeBy(60_000)
            assertEquals(1000195L, assertIs<CheckState.Available>(h.checker.checkNow()).info.versionCode)
        }

    @Test fun staleSkipDoesNotMutateSettingsOrState() =
        runTest {
            val h = Harness(this)
            val available = h.checker.checkNow()
            h.checker.skip(123)
            runCurrent()
            assertEquals(available, h.checker.state.value)
            assertEquals(Keys.ABSENT, h.settings.get(Keys.SKIPPED_VERSION_CODE))
        }

    @Test fun failedSkipRetainsCardUntilDurableRetrySucceeds() =
        runTest {
            val h = Harness(this)
            val available = assertIs<CheckState.Available>(h.checker.checkNow())
            h.settings.failNextSet = SettingsError.WriteFailed
            h.checker.skip(available.info.versionCode)
            runCurrent()
            assertEquals(available, h.checker.state.value)
            assertEquals(SettingsError.WriteFailed, h.checker.writeError.value)
            assertEquals(0, h.notifications.cancelled)
            h.checker.skip(available.info.versionCode)
            runCurrent()
            assertIs<CheckState.Idle>(h.checker.state.value)
            assertNull(h.checker.writeError.value)
        }

    @Test fun restartRestoresAvailableWithoutAnyRequest() =
        runTest {
            val first = Harness(this)
            val available = first.checker.checkNow()
            val second = Harness(this, store = first.store, settings = first.settings)
            second.checker.initialize()
            assertEquals(available, second.checker.state.value)
            assertEquals(0, second.source.calls)
            assertEquals(0, second.notifications.posted.size)
        }

    @Test fun installedCachedVersionIsClearedAndNotificationCancelled() =
        runTest {
            val store =
                MemoryUpdateStore(
                    UpdateStoreSnapshot(
                        manifest(),
                        TestClock.DEFAULT_NOW,
                        TestClock.DEFAULT_NOW,
                        TestClock.DEFAULT_NOW + DAY,
                        0,
                    ),
                )
            val h = Harness(this, build = updateBuild(versionCode = 1000095), store = store)
            h.checker.initialize()
            assertIs<CheckState.Idle>(h.checker.state.value)
            assertNull(store.value?.manifest)
            assertEquals(1, h.notifications.cancelled)
            assertEquals(0, h.source.calls)
        }

    @Test fun corruptCacheRecoversWithoutAnUnsolicitedGet() =
        runTest {
            val store = MemoryUpdateStore().also { it.readError = UpdateStoreError.CORRUPT }
            val h = Harness(this, store = store)
            h.checker.initialize()
            assertEquals(CheckState.Idle(null), h.checker.state.value)
            assertEquals(h.clock.now() + DAY, store.value?.nextDueAtMs)
            assertEquals(0, h.source.calls)
        }

    @Test fun cacheRenameBeforePreferenceWriteIsReconciledAfterRestart() =
        runTest {
            val h = Harness(this)
            h.checker.initialize()
            h.settings.failNextSet = SettingsError.WriteFailed
            val available = h.checker.checkNow()
            assertNotNull(h.store.value?.manifest)
            assertEquals(Keys.ABSENT, h.settings.get(Keys.LAST_CHECK_AT))
            assertEquals(SettingsError.WriteFailed, h.checker.writeError.value)
            val restarted = Harness(this, store = h.store, settings = h.settings)
            restarted.checker.initialize()
            assertEquals(available, restarted.checker.state.value)
            assertEquals(h.clock.now(), h.settings.get(Keys.LAST_CHECK_AT))
            assertEquals(0, restarted.source.calls)
        }

    @Test fun cacheWriteFailureIsExposedWithoutAcknowledgingDurability() =
        runTest {
            val h = Harness(this)
            h.checker.initialize()
            val old = h.store.value
            h.store.writeError = UpdateStoreError.IO
            assertIs<CheckState.Available>(h.checker.checkNow())
            assertEquals(old, h.store.value)
            assertEquals(SettingsError.WriteFailed, h.checker.writeError.value)
        }

    @Test fun scheduledNotificationPostsOnceAcrossRunsAndRestart() =
        runTest {
            val h = Harness(this)
            h.scheduled()
            h.scheduled()
            assertEquals(1, h.notifications.posted.size)
            val restarted = Harness(this, store = h.store, settings = h.settings)
            restarted.scheduled()
            assertEquals(0, restarted.notifications.posted.size)
        }

    @Test fun manualSuppressionPreventsLaterScheduledNotification() =
        runTest {
            val h = Harness(this)
            h.checker.checkNow()
            h.scheduled()
            assertEquals(0, h.notifications.posted.size)
            h.source.result = Outcome.Success(manifest("1.0.1"))
            h.scheduled()
            assertEquals(1, h.notifications.posted.size)
        }

    @Test fun deniedPermissionOrChannelLeavesNotificationMarkerUnset() =
        runTest {
            val h = Harness(this)
            h.notifications.result = UpdatePostResult.UNAVAILABLE
            h.scheduled()
            assertEquals(Keys.ABSENT, h.settings.get(Keys.NOTIFIED_VERSION_CODE))
            h.notifications.result = UpdatePostResult.ACCEPTED
            h.scheduled()
            assertEquals(1000095L, h.settings.get(Keys.NOTIFIED_VERSION_CODE))
        }

    @Test fun androidTooOldDoesNotDispatchButStillShowsReleaseCard() =
        runTest {
            val h = Harness(this, target = UpdateRuntimeTarget.Android("arm64-v8a", 25))
            assertIs<CheckState.Available>(h.scheduled())
            assertEquals(0, h.notifications.posted.size)
            assertEquals(Keys.ABSENT, h.settings.get(Keys.NOTIFIED_VERSION_CODE))
        }

    @Test fun preferredDeviceAbiWinsOverInstalledApkAbi() =
        runTest {
            val h = Harness(this)
            assertEquals("armeabi-v7a", h.build.apkAbi)
            assertEquals("arm64-v8a", assertIs<CheckState.Available>(h.checker.checkNow()).info.apk?.abi)
        }

    @Test fun missingPreferredAbiKeepsReleaseOnlyCard() =
        runTest {
            listOf(null, "riscv64", "x86_64").forEach { abi ->
                val h = Harness(this, target = UpdateRuntimeTarget.Android(abi, 37))
                val info = assertIs<CheckState.Available>(h.checker.checkNow()).info
                assertNull(info.apk)
                assertEquals("$UPDATE_REPO/releases/tag/v1.0.0", info.releaseUrl)
            }
        }

    @Test fun desktopSelectionExposesCompatibilityReasonWithoutAnApk() =
        runTest {
            val h =
                Harness(
                    this,
                    build = updateBuild(platform = BuildInfo.Platform.DESKTOP),
                    target = UpdateRuntimeTarget.Desktop("6.1"),
                )
            val info = assertIs<CheckState.Available>(h.checker.checkNow()).info
            assertNull(info.apk)
            assertNotNull(info.desktopSelection)
        }

    @Test fun upToDateAndOlderManifestClearCachedAvailability() =
        runTest {
            val h = Harness(this)
            h.checker.checkNow()
            h.source.result = Outcome.Success(manifest("0.1.0"))
            advanceTimeBy(60_000)
            assertIs<CheckState.Idle>(h.checker.checkNow())
            assertNull(h.store.value?.manifest)
            assertEquals(1, h.notifications.cancelled)
        }

    @Test fun failedNotificationBookkeepingRemainsExplicitAndRetryable() =
        runTest {
            val h = Harness(this)
            h.checker.initialize()
            h.notifications.onPost = { h.settings.failNextSet = SettingsError.WriteFailed }
            h.scheduled()
            assertEquals(Keys.ABSENT, h.settings.get(Keys.NOTIFIED_VERSION_CODE))
            assertEquals(SettingsError.WriteFailed, h.checker.writeError.value)
            h.notifications.onPost = null
            h.scheduled()
            assertEquals(1000095L, h.settings.get(Keys.NOTIFIED_VERSION_CODE))
            assertEquals(1, h.notifications.posted.size)
            assertNull(h.checker.writeError.value)
        }
}
