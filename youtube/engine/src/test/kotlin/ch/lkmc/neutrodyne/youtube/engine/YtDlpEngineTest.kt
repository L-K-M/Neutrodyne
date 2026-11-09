// SPDX-License-Identifier: Unlicense
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.testing.FakeEnginePlatformPreflight
import ch.lkmc.neutrodyne.core.testing.FakeEngineSettings
import ch.lkmc.neutrodyne.core.testing.FakeEngineStore
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeHealth
import ch.lkmc.neutrodyne.core.testing.FakeYtxTransport
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.api.BreakerState
import ch.lkmc.neutrodyne.youtube.api.EngineAvailability
import ch.lkmc.neutrodyne.youtube.api.EngineUpdateOutcome
import ch.lkmc.neutrodyne.youtube.api.EngineVersionSource
import ch.lkmc.neutrodyne.youtube.api.ExtractionGate
import ch.lkmc.neutrodyne.youtube.api.PlatformLimitation
import ch.lkmc.neutrodyne.youtube.api.PrewarmReason
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import ch.lkmc.neutrodyne.youtube.api.YouTubeExtractionHealth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YtDlpEngineTest {
    @Test fun readsAreInertAndAllNormalInstalledStatesKeepCapabilities() =
        runTest {
            val settings = FakeEngineSettings()
            val health = FakeYouTubeHealth()
            val f = Fixture(this, settings, settings, healthDelegate = health)
            runCurrent()
            assertEquals(EngineAvailability.STOPPED, f.engine.status.value.availability)
            assertEquals(EngineVersionSource.BUNDLED, f.engine.status.value.source)
            assertFalse(f.engine.status.value.jsChallenges)
            f.assertCapabilities(null)
            assertTrue(f.transport.calls.isEmpty())
            assertTrue(f.transport.prewarms.isEmpty())
            assertEquals(0, f.store.preparations)
            f.host(HostState.STARTING)
            assertEquals(EngineAvailability.STARTING, f.engine.status.value.availability)
            f.host(HostState.COMPILING)
            assertEquals(EngineAvailability.STARTING, f.engine.status.value.availability)
            f.host(HostState.READY)
            assertEquals(EngineAvailability.READY, f.engine.status.value.availability)
            f.host(HostState.STOPPED, HostExit.CRASH)
            assertEquals(EngineAvailability.STOPPED, f.engine.status.value.availability)
            f.assertCapabilities(null)
            health.state.value =
                health.state.value.copy(
                    breaker = BreakerState.OPEN,
                    breakerOpenUntil = Long.MAX_VALUE,
                    rateLimitedUntil = Long.MAX_VALUE,
                )
            health.gate = ExtractionGate.Deny(Long.MAX_VALUE, TransientKind.BREAKER_OPEN)
            runCurrent()
            f.assertCapabilities(null)
            assertEquals(0, f.settings.state.value.startFailures)
        }

    @Test fun prewarmCoalescesStoppedAndStartingButReadyRefreshesIdleTimer() =
        runTest {
            val f = fixture()
            repeat(5) { f.engine.prewarm(PrewarmReason.PROJECTION) }
            assertEquals(listOf(PrewarmReason.PROJECTION), f.transport.prewarms)
            f.host(HostState.STARTING)
            f.engine.prewarm(PrewarmReason.SEARCH)
            assertEquals(1, f.transport.prewarms.size)
            f.host(HostState.READY)
            f.engine.prewarm(PrewarmReason.DOWNLOAD)
            f.engine.prewarm(PrewarmReason.SCREEN)
            assertEquals(
                listOf(PrewarmReason.PROJECTION, PrewarmReason.DOWNLOAD, PrewarmReason.SCREEN),
                f.transport.prewarms,
            )
            f.host(HostState.STOPPED, HostExit.IDLE_STOP)
            f.engine.prewarm(PrewarmReason.SEARCH)
            assertEquals(PrewarmReason.SEARCH, f.transport.prewarms.last())
        }

    @Test fun thirdConsecutiveStartFailureExternalizesAndRetryClears() =
        runTest {
            val f = fixture()
            repeat(3) { index ->
                f.failedStart()
                f.assertCapabilities(if (index == 2) ExternalReason.ENGINE_FAILED else null)
            }
            assertEquals(3, f.settings.state.value.startFailures)
            f.host(HostState.STARTING)
            f.host(HostState.READY)
            f.assertCapabilities(ExternalReason.ENGINE_FAILED)
            assertEquals(3, f.settings.state.value.startFailures)
            f.host(HostState.STOPPED, HostExit.START_FAILED)
            val count = f.transport.prewarms.size
            f.engine.prewarm(PrewarmReason.SCREEN)
            assertEquals(count, f.transport.prewarms.size)
            f.engine.retryStart()
            runCurrent()
            assertEquals(0, f.settings.state.value.startFailures)
            f.assertCapabilities(null)
            assertEquals(count + 1, f.transport.prewarms.size)
            f.host(HostState.STARTING)
            f.host(HostState.READY)
            assertEquals(EngineAvailability.READY, f.engine.status.value.availability)
        }

    @Test fun successResetsConsecutiveCountAndReadyCrashNeverCountsAsStartFailure() =
        runTest {
            val f = fixture()
            repeat(2) { f.failedStart() }
            f.host(HostState.STARTING)
            f.host(HostState.READY)
            assertEquals(0, f.settings.state.value.startFailures)
            f.host(HostState.STOPPED, HostExit.CRASH)
            f.failedStart()
            assertEquals(1, f.settings.state.value.startFailures)
            f.assertCapabilities(null)
            // Diagnostic changes in the same failed-stop do not count the attempt twice.
            f.transport.status.value =
                f.transport.status.value
                    .copy(queued = 1)
            runCurrent()
            assertEquals(1, f.settings.state.value.startFailures)
        }

    @Test fun persistedFailureIdentityIncludesAppEngineAndPythonAndRestartRetainsCount() =
        runTest {
            val identity = CompileMarker(42, "2026.08.19", "3.14")
            val identities =
                listOf(
                    identity,
                    identity.copy(appVersionCode = 41),
                    identity.copy(engineVersion = "old"),
                    identity.copy(pythonMinor = "3.13"),
                )
            for (saved in identities) {
                val settings = FakeEngineSettings()
                settings.state.value = EngineSettingsSnapshot(startFailures = 3, failureIdentity = saved)
                val f = Fixture(this, settings, settings)
                // Capabilities stay conservative until the settings load completes.
                f.assertCapabilities(ExternalReason.NOT_YET_AVAILABLE)
                runCurrent()
                f.assertCapabilities(if (saved == identity) ExternalReason.ENGINE_FAILED else null)
                assertEquals(if (saved == identity) 3 else 0, f.settings.state.value.startFailures)
                val restarted =
                    YtDlpEngine(
                        f.transport,
                        f.store,
                        f.settings,
                        f.preflight,
                        identity,
                        f.health,
                        f.clock,
                        backgroundScope,
                    )
                runCurrent()
                assertEquals(f.engine.status.value, restarted.status.value)
            }
        }

    @Test fun disabledShutsDownRejectsLateReadyAndReenableRequiresFreshStart() =
        runTest {
            val f = fixture()
            f.host(HostState.STARTING)
            f.settings.state.value =
                f.settings.state.value
                    .copy(enabled = false)
            runCurrent()
            assertEquals(1, f.transport.shutdowns)
            f.assertCapabilities(ExternalReason.DISABLED_BY_USER)
            f.host(HostState.READY)
            assertEquals(EngineAvailability.DISABLED, f.engine.status.value.availability)
            f.engine.prewarm(PrewarmReason.PROJECTION)
            assertTrue(f.transport.prewarms.isEmpty())
            f.settings.state.value =
                f.settings.state.value
                    .copy(enabled = true)
            runCurrent()
            assertEquals(EngineAvailability.STOPPED, f.engine.status.value.availability)
            f.assertCapabilities(null)
            f.host(HostState.STOPPED, HostExit.SHUTDOWN)
            f.engine.prewarm(PrewarmReason.PROJECTION)
            f.host(HostState.STARTING)
            f.host(HostState.READY)
            assertEquals(EngineAvailability.READY, f.engine.status.value.availability)
        }

    @Test fun missingEngineWinsDisabledAndPlatformRetryNeverBindsWhileUnusable() =
        runTest {
            for (limitation in PlatformLimitation.entries) {
                val f = fixture(EngineSettingsSnapshot(enabled = false), limitation)
                val absent =
                    limitation == PlatformLimitation.NO_ENGINE ||
                        limitation == PlatformLimitation.PROCESS_32_BIT
                f.assertCapabilities(if (absent) ExternalReason.NOT_IN_THIS_APK else ExternalReason.DISABLED_BY_USER)
                f.settings.state.value =
                    f.settings.state.value
                        .copy(enabled = true)
                runCurrent()
                f.engine.retryStart()
                runCurrent()
                f.assertCapabilities(if (absent) ExternalReason.NOT_IN_THIS_APK else ExternalReason.ENGINE_FAILED)
                assertEquals(limitation, f.engine.status.value.platformLimitation)
                assertTrue(f.transport.prewarms.isEmpty())
                val checks = f.preflight.checks
                f.preflight.result = null
                f.engine.retryStart()
                runCurrent()
                assertEquals(checks + 1, f.preflight.checks)
                f.assertCapabilities(null)
                assertEquals(1, f.transport.prewarms.size)
            }
        }

    @Test fun preparationNeverRunsInlineAndFailureIsExplicit() =
        runTest {
            val f = fixture()
            for (preparation in listOf(EnginePreparation.UNPREPARED, EnginePreparation.PREPARING)) {
                f.store.preparation.value = preparation
                runCurrent()
                f.engine.prewarm(PrewarmReason.SCREEN)
                assertEquals(EngineAvailability.PREPARING, f.engine.status.value.availability)
                f.assertCapabilities(null)
                assertEquals(0, f.store.preparations)
                assertTrue(f.transport.prewarms.isEmpty())
            }
            f.store.preparation.value = EnginePreparation.FAILED
            runCurrent()
            f.assertCapabilities(ExternalReason.ENGINE_FAILED)
            f.store.preparation.value = EnginePreparation.COMPILED
            runCurrent()
            f.engine.prewarm(PrewarmReason.SCREEN)
            assertEquals(1, f.transport.prewarms.size)
        }

    @Test fun retryWhileSettingsLoadingWinsOverPersistedFailureCount() =
        runTest {
            val settings =
                FakeEngineSettings().also {
                    it.state.value =
                        EngineSettingsSnapshot(
                            startFailures = 3,
                            failureIdentity = CompileMarker(42, "2026.08.19", "3.14"),
                        )
                }
            val loaded = CompletableDeferred<Unit>()
            val delayed =
                object : EngineSettings by settings {
                    override suspend fun awaitLoaded() {
                        loaded.await()
                    }
                }
            val f = Fixture(this, settings, delayed)
            f.engine.retryStart()
            loaded.complete(Unit)
            runCurrent()
            f.assertCapabilities(null)
            assertEquals(0, settings.state.value.startFailures)
            assertEquals(1, f.transport.prewarms.size)
        }

    @Test fun lateReadyCannotEraseAFailedStartAndUnusableRetryCanRecover() =
        runTest {
            val f = fixture()
            f.failedStart()
            f.host(HostState.READY)
            assertEquals(1, f.settings.state.value.startFailures)
            assertEquals(EngineAvailability.STOPPED, f.engine.status.value.availability)
            f.host(HostState.UNUSABLE)
            f.assertCapabilities(ExternalReason.ENGINE_FAILED)
            f.engine.retryStart()
            runCurrent()
            f.assertCapabilities(null)
            assertEquals(1, f.transport.prewarms.size)
            f.host(HostState.STARTING)
            f.host(HostState.READY)
            assertEquals(EngineAvailability.READY, f.engine.status.value.availability)
        }

    @Test fun writeFailureLeavesMemoryAuthoritativeAndRetryRepairsPersistence() =
        runTest {
            val settings = FakeEngineSettings()
            var fail = false
            val adapter =
                object : EngineSettings by settings {
                    override suspend fun recordStartFailures(
                        identity: CompileMarker,
                        count: Int,
                    ) {
                        if (fail) {
                            fail = false
                            error("Injected settings failure")
                        }
                        settings.recordStartFailures(identity, count)
                    }
                }
            val f = Fixture(this, settings, adapter)
            runCurrent()
            fail = true
            f.failedStart()
            assertEquals(0, settings.state.value.startFailures)
            f.failedStart()
            assertEquals(2, settings.state.value.startFailures)
            f.failedStart()
            f.assertCapabilities(ExternalReason.ENGINE_FAILED)
            f.engine.retryStart()
            runCurrent()
            assertEquals(0, settings.state.value.startFailures)
            f.assertCapabilities(null)
        }

    @Test fun capabilitiesStayOffUntilSettingsLoadRevealsPersistedOptOut() =
        runTest {
            val settings = FakeEngineSettings()
            val loaded = CompletableDeferred<Unit>()
            // A real adapter emits the default snapshot (enabled) until the store loads.
            val exposed = MutableStateFlow(EngineSettingsSnapshot())
            val delayed =
                object : EngineSettings by settings {
                    override val state: StateFlow<EngineSettingsSnapshot> = exposed

                    override suspend fun awaitLoaded() {
                        loaded.await()
                        exposed.value = EngineSettingsSnapshot(enabled = false)
                    }
                }
            val f = Fixture(this, settings, delayed)
            f.assertCapabilities(ExternalReason.NOT_YET_AVAILABLE)
            assertEquals(EngineAvailability.STOPPED, f.engine.status.value.availability)
            f.engine.prewarm(PrewarmReason.SCREEN)
            assertTrue(f.transport.prewarms.isEmpty())
            assertTrue(f.transport.calls.isEmpty())
            loaded.complete(Unit)
            runCurrent()
            f.assertCapabilities(ExternalReason.DISABLED_BY_USER)
            assertEquals(EngineAvailability.DISABLED, f.engine.status.value.availability)
            assertTrue(f.transport.prewarms.isEmpty())
            assertTrue(f.transport.calls.isEmpty())
        }

    @Test fun successfulStartWhileLoadingResetsPersistedFailures() =
        runTest {
            val settings =
                FakeEngineSettings().also {
                    it.state.value =
                        EngineSettingsSnapshot(
                            startFailures = 2,
                            failureIdentity = CompileMarker(42, "2026.08.19", "3.14"),
                        )
                }
            val loaded = CompletableDeferred<Unit>()
            val delayed =
                object : EngineSettings by settings {
                    override suspend fun awaitLoaded() {
                        loaded.await()
                    }
                }
            val f = Fixture(this, settings, delayed)
            f.transport.status.value =
                f.transport.status.value
                    .copy(state = HostState.READY)
            loaded.complete(Unit)
            runCurrent()
            assertEquals(0, settings.state.value.startFailures)
            assertEquals(EngineAvailability.READY, f.engine.status.value.availability)
        }

    @Test fun liveOptOutDuringBlockedHealthLoadShutsDownAndBlocksPrewarm() =
        runTest {
            val settings = FakeEngineSettings()
            val health = GatedYouTubeHealth()
            val f = Fixture(this, settings, settings, healthDelegate = health)
            runCurrent()
            // Settings load is done; the health barrier is still held.
            f.assertCapabilities(null)
            f.settings.state.value =
                f.settings.state.value
                    .copy(enabled = false)
            runCurrent()
            assertEquals(1, f.transport.shutdowns)
            f.assertCapabilities(ExternalReason.DISABLED_BY_USER)
            assertEquals(EngineAvailability.DISABLED, f.engine.status.value.availability)
            f.engine.prewarm(PrewarmReason.SCREEN)
            assertTrue(f.transport.prewarms.isEmpty())
            assertTrue(f.transport.calls.isEmpty())
            // The engine version report stays behind the health barrier.
            assertEquals(1, health.loads)
            assertFalse(health.reports.contains("version" to "2026.08.19"))
            health.release()
            runCurrent()
            assertTrue(health.reports.contains("version" to "2026.08.19"))
        }

    @Test fun hostTransitionsDuringBlockedHealthLoadAreObserved() =
        runTest {
            val settings = FakeEngineSettings()
            val health = GatedYouTubeHealth()
            val f = Fixture(this, settings, settings, healthDelegate = health)
            runCurrent()
            f.host(HostState.STARTING)
            f.host(HostState.STOPPED, HostExit.START_FAILED)
            assertEquals(1, f.settings.state.value.startFailures)
            f.assertCapabilities(null)
            health.release()
            runCurrent()
            assertEquals(1, f.settings.state.value.startFailures)
        }

    @Test fun engineVersionReportFollowsTheHealthBarrier() =
        runTest {
            val settings = FakeEngineSettings()
            val health = GatedYouTubeHealth()
            val f = Fixture(this, settings, settings, healthDelegate = health)
            runCurrent()
            f.assertCapabilities(null)
            assertEquals(1, health.loads)
            assertTrue(health.reports.isEmpty())
            health.release()
            runCurrent()
            assertTrue(health.reports.contains("version" to "2026.08.19"))
            assertEquals(0, f.settings.state.value.startFailures)
        }

    @Test fun updateHooksRouteManualCheckBreakerEdgeAndReset() =
        runTest {
            val settings = FakeEngineSettings()
            val health = FakeYouTubeHealth()
            val hooks = RecordingHooks()
            val f = Fixture(this, settings, settings, healthDelegate = health, hooks = hooks)
            runCurrent()
            assertEquals(0, hooks.immediates)
            health.state.value = health.state.value.copy(breaker = BreakerState.OPEN)
            runCurrent()
            assertEquals(1, hooks.immediates)
            // Only a closed → open edge re-arms the immediate check.
            health.state.value = health.state.value.copy(breaker = BreakerState.OPEN)
            runCurrent()
            assertEquals(1, hooks.immediates)
            health.state.value = health.state.value.copy(breaker = BreakerState.CLOSED)
            runCurrent()
            health.state.value = health.state.value.copy(breaker = BreakerState.OPEN)
            runCurrent()
            assertEquals(2, hooks.immediates)
            assertEquals(EngineUpdateOutcome.UpToDate, f.engine.checkForUpdate())
            assertEquals(listOf(EngineUpdateTrigger.MANUAL), hooks.triggers)
            f.engine.resetToBundled()
            assertEquals(1, hooks.resets)
        }

    @Test fun engineWithoutHooksReportsUpdateUnavailable() =
        runTest {
            val f = fixture()
            assertEquals(
                EngineUpdateOutcome.Failed(TransientKind.ENGINE_UNAVAILABLE),
                f.engine.checkForUpdate(),
            )
            f.engine.resetToBundled()
        }

    @Test fun statusReportsTheBundleBeforeLoadStatusFillsActive() =
        runTest {
            val engine =
                YtDlpEngine(
                    FakeYtxTransport(),
                    FakeEngineStore(),
                    FakeEngineSettings(),
                    FakeEnginePlatformPreflight(),
                    CompileMarker(42, "2026.08.19", "3.14"),
                    FakeYouTubeHealth(),
                    TestClock(),
                    backgroundScope,
                )
            assertEquals("2026.08.19", engine.status.value.version)
        }

    @Test fun fastHealthLoadDoesNotSkipPersistedFailureRestore() =
        runTest {
            val identity = CompileMarker(42, "2026.08.19", "3.14")
            val settings =
                FakeEngineSettings().also {
                    it.state.value =
                        EngineSettingsSnapshot(startFailures = 3, failureIdentity = identity)
                }
            // The adapter exposes the default snapshot until the store loads, as a real one does.
            val exposed = MutableStateFlow(EngineSettingsSnapshot())
            val settingsLoaded = CompletableDeferred<Unit>()
            val delayedSettings =
                object : EngineSettings by settings {
                    override val state: StateFlow<EngineSettingsSnapshot> = exposed

                    override suspend fun awaitLoaded() {
                        settingsLoaded.await()
                        exposed.value =
                            EngineSettingsSnapshot(startFailures = 3, failureIdentity = identity)
                    }
                }
            // The health load completes immediately, ahead of the portable settings load.
            val f = Fixture(this, settings, delayedSettings)
            runCurrent()
            f.assertCapabilities(ExternalReason.NOT_YET_AVAILABLE)
            settingsLoaded.complete(Unit)
            runCurrent()
            f.assertCapabilities(ExternalReason.ENGINE_FAILED)
        }

    @Test fun retryDuringHealthyStartAndReadyKeepsTheCurrentAttempt() =
        runTest {
            val f = fixture()
            f.host(HostState.STARTING)
            f.engine.retryStart()
            f.host(HostState.READY)
            assertEquals(EngineAvailability.READY, f.engine.status.value.availability)
            f.engine.retryStart()
            assertEquals(EngineAvailability.READY, f.engine.status.value.availability)
            assertEquals(1, f.transport.prewarms.size)
        }

    private fun TestScope.fixture(
        prefs: EngineSettingsSnapshot = EngineSettingsSnapshot(),
        limitation: PlatformLimitation? = null,
    ): Fixture {
        val settings = FakeEngineSettings().also { it.state.value = prefs }
        return Fixture(this, settings, settings, limitation).also { runCurrent() }
    }

    private class Fixture(
        private val scope: TestScope,
        val settings: FakeEngineSettings,
        adapter: EngineSettings,
        limitation: PlatformLimitation? = null,
        healthDelegate: YouTubeExtractionHealth = FakeYouTubeHealth(),
        hooks: EngineUpdateHooks? = null,
    ) {
        val transport = FakeYtxTransport()
        val store =
            FakeEngineStore().also {
                it.preparation.value = EnginePreparation.COMPILED
                it.active.value = EngineActive("2026.08.19", "2026.08.19", null, 0)
            }
        val preflight = FakeEnginePlatformPreflight().also { it.result = limitation }
        val health = healthDelegate
        val clock = TestClock()
        val engine =
            YtDlpEngine(
                transport,
                store,
                adapter,
                preflight,
                CompileMarker(42, "2026.08.19", "3.14"),
                health,
                clock,
                scope.backgroundScope,
                hooks,
            )

        fun host(
            state: HostState,
            exit: HostExit? = null,
        ) {
            transport.status.value = transport.status.value.copy(state = state, lastExit = exit)
            scope.runCurrent()
        }

        fun failedStart() {
            host(HostState.STARTING)
            host(HostState.STOPPED, HostExit.START_FAILED)
        }

        fun assertCapabilities(reason: ExternalReason?) {
            val caps = engine.capabilities.value
            assertEquals(reason, caps.externalReason)
            assertEquals(
                List(5) { reason == null },
                listOf(
                    caps.inAppPlayback,
                    caps.downloads,
                    caps.channelSearch,
                    caps.enrichment,
                    caps.backCatalogue,
                ),
            )
        }
    }

    private class RecordingHooks : EngineUpdateHooks {
        val triggers = mutableListOf<EngineUpdateTrigger>()
        var immediates = 0
        var resets = 0
        var outcome: EngineUpdateOutcome = EngineUpdateOutcome.UpToDate

        override suspend fun runUpdate(trigger: EngineUpdateTrigger): EngineUpdateOutcome {
            triggers += trigger
            return outcome
        }

        override suspend fun resetToBundled() {
            resets++
        }

        override fun requestImmediateCheck() {
            immediates++
        }
    }

    /** Blocks the health load so the engine's observation paths can be probed mid-load. */
    private class GatedYouTubeHealth(
        private val delegate: FakeYouTubeHealth = FakeYouTubeHealth(),
    ) : YouTubeExtractionHealth by delegate {
        private val barrier = CompletableDeferred<Unit>()
        var loads = 0
        val reports: MutableList<Any> get() = delegate.reports

        override suspend fun awaitLoaded() {
            loads++
            barrier.await()
        }

        fun release() {
            barrier.complete(Unit)
        }
    }
}
