// SPDX-License-Identifier: Unlicense
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ch.lkmc.neutrodyne.youtube.ytdlp

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import ch.lkmc.neutrodyne.core.datastore.DataStoreBindings
import ch.lkmc.neutrodyne.core.datastore.DeviceSettingsStore
import ch.lkmc.neutrodyne.core.datastore.SettingsStore
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys
import ch.lkmc.neutrodyne.youtube.api.EngineUpdateOutcome
import ch.lkmc.neutrodyne.youtube.api.EngineUpdatePolicy
import ch.lkmc.neutrodyne.youtube.engine.CompileMarker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The device-bound check state and the portable policy round-trip through real
 * `preferences_pb` files (04's runner: `youtube.engine_*` keys; 01 DataStore files). A
 * "restart" is a second store pair over the same files after the first pair's scope is
 * cancelled — DataStore holds one live registration per file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataStoreEngineSettingsTest {
    @get:Rule val temporary = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val identity = CompileMarker(1, "2026.08.19", "3.14")

    @Test fun checkHeartbeatAndFailuresSurviveAStoreRestart() =
        runTest(dispatcher) {
            val adapterScope = CoroutineScope(SupervisorJob() + dispatcher)
            val first = stores()
            val settings = DataStoreEngineSettings(first.first, first.second, identity, adapterScope)
            settings.awaitLoaded()
            settings.recordCheck(42L, EngineUpdateOutcome.Activated("2026.11.05"))
            settings.recordHeartbeat(777L)
            settings.recordStartFailures(identity, 3)
            assertEquals(42L, first.second.get(YouTubeSettingKeys.ENGINE_LAST_CHECK_AT))
            assertEquals(
                "activated:2026.11.05",
                first.second.get(YouTubeSettingKeys.ENGINE_LAST_OUTCOME),
            )
            assertEquals(777L, first.second.get(YouTubeSettingKeys.ENGINE_HEARTBEAT_AT))
            first.third.cancel()
            val second = stores()
            val restarted =
                DataStoreEngineSettings(second.first, second.second, identity, adapterScope)
            restarted.awaitLoaded()
            assertEquals(42L, restarted.state.value.lastCheckAtMs)
            assertEquals(
                EngineUpdateOutcome.Activated("2026.11.05"),
                restarted.state.value.lastOutcome,
            )
            assertEquals(777L, restarted.state.value.heartbeatAtMs)
            assertEquals(3, restarted.state.value.startFailures)
            assertEquals(identity, restarted.state.value.failureIdentity)
            second.third.cancel()
            adapterScope.cancel()
        }

    @Test fun failureCountsResetWhenTheCompileIdentityChanges() =
        runTest(dispatcher) {
            val adapterScope = CoroutineScope(SupervisorJob() + dispatcher)
            val first = stores()
            val settings = DataStoreEngineSettings(first.first, first.second, identity, adapterScope)
            settings.awaitLoaded()
            settings.recordStartFailures(identity, 4)
            first.third.cancel()
            val upgraded = CompileMarker(1, "2026.11.05", "3.14")
            val second = stores()
            val restarted =
                DataStoreEngineSettings(second.first, second.second, upgraded, adapterScope)
            restarted.awaitLoaded()
            assertEquals(0, restarted.state.value.startFailures)
            assertEquals(upgraded, restarted.state.value.failureIdentity)
            second.third.cancel()
            adapterScope.cancel()
        }

    @Test fun enabledAndPolicyLiveFromThePortableStore() =
        runTest(dispatcher) {
            val adapterScope = CoroutineScope(SupervisorJob() + dispatcher)
            val (portable, device, storeScope) = stores()
            val settings = DataStoreEngineSettings(portable, device, identity, adapterScope)
            settings.awaitLoaded()
            assertTrue(settings.state.value.enabled)
            assertEquals(EngineUpdatePolicy.APPROVED, settings.state.value.updatePolicy)
            portable.set(YouTubeSettingKeys.ENGINE_UPDATES, EngineUpdatePolicy.OFF)
            portable.set(YouTubeSettingKeys.ENGINE_ENABLED, false)
            assertEquals(EngineUpdatePolicy.OFF, settings.state.value.updatePolicy)
            assertFalse(settings.state.value.enabled)
            // The check state never crosses into the portable file.
            assertFailsWith<IllegalArgumentException> {
                portable.get(YouTubeSettingKeys.ENGINE_LAST_CHECK_AT)
            }
            storeScope.cancel()
            adapterScope.cancel()
        }

    /** A store pair on the shared files; the returned scope owns both registrations. */
    private fun stores(): Triple<SettingsStore, DeviceSettingsStore, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val portable =
            DataStoreBindings.settingsStore(
                PreferenceDataStoreFactory.createWithPath(
                    scope = scope,
                    produceFile = {
                        temporary.root.path
                            .toPath()
                            .resolve("settings.preferences_pb")
                    },
                ),
            )
        val device =
            DataStoreBindings.deviceSettingsStore(
                PreferenceDataStoreFactory.createWithPath(
                    scope = scope,
                    produceFile = {
                        temporary.root.path
                            .toPath()
                            .resolve("device.preferences_pb")
                    },
                ),
            )
        return Triple(portable, device, scope)
    }
}
