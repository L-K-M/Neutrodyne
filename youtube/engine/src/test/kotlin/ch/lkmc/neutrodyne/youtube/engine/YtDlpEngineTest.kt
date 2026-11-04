// SPDX-License-Identifier: Unlicense
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.testing.FakeEnginePlatformPreflight
import ch.lkmc.neutrodyne.core.testing.FakeEngineSettings
import ch.lkmc.neutrodyne.core.testing.FakeEngineStore
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeHealth
import ch.lkmc.neutrodyne.core.testing.FakeYtxTransport
import ch.lkmc.neutrodyne.youtube.api.BreakerState
import ch.lkmc.neutrodyne.youtube.api.EngineAvailability
import ch.lkmc.neutrodyne.youtube.api.EngineVersionSource
import ch.lkmc.neutrodyne.youtube.api.ExtractionGate
import ch.lkmc.neutrodyne.youtube.api.PlatformLimitation
import ch.lkmc.neutrodyne.youtube.api.PrewarmReason
import ch.lkmc.neutrodyne.youtube.api.TransientKind
import kotlinx.coroutines.CompletableDeferred
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
            val f = fixture()
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
            f.health.state.value =
                f.health.state.value.copy(
                    breaker = BreakerState.OPEN,
                    breakerOpenUntil = Long.MAX_VALUE,
                    rateLimitedUntil = Long.MAX_VALUE,
                )
            f.health.gate = ExtractionGate.Deny(Long.MAX_VALUE, TransientKind.BREAKER_OPEN)
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
                f.assertCapabilities(if (saved == identity) ExternalReason.ENGINE_FAILED else null)
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
    ) {
        val transport = FakeYtxTransport()
        val store = FakeEngineStore().also { it.preparation.value = EnginePreparation.COMPILED }
        val preflight = FakeEnginePlatformPreflight().also { it.result = limitation }
        val health = FakeYouTubeHealth()
        val engine =
            YtDlpEngine(
                transport,
                store,
                adapter,
                preflight,
                CompileMarker(42, "2026.08.19", "3.14"),
                health,
                scope.backgroundScope,
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
}
