// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.playback.api.PlayResult
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeteredStreamingGateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE.copy(isMetered = true))
    private val settings = FakeSettingsRepository()
    private val prefs = PlaybackPrefs(Provider { settings }).also { it.start(scope) }
    private val gate = MeteredStreamingGate(network, prefs, scope)

    @After fun close() {
        scope.cancel()
    }

    private fun policy(value: StreamOnMetered) {
        runBlocking { settings.set(PlaybackSettingKeys.STREAM_ON_METERED, value) }
    }

    @Test fun askDoesNotGrantOrMutateForUiStart() {
        policy(StreamOnMetered.ASK)
        assertEquals(PlayResult.NeedsMeteredConsent(1), gate.check(1, false, StartOrigin.UI))
        assertEquals(PlayResult.NeedsMeteredConsent(1), gate.pendingConsent.value)
        assertTrue(gate.blockBeforeNext(false))
    }

    @Test fun onceGrantClearsWhenUnmeteredAndDoesNotSurviveNewGate() {
        policy(StreamOnMetered.ASK)
        gate.grant()
        assertNull(gate.check(1, false, StartOrigin.UI))
        assertFalse(gate.blockBeforeNext(false))
        assertEquals(
            PlayResult.NeedsMeteredConsent(1),
            MeteredStreamingGate(network, prefs, scope).check(1, false, StartOrigin.UI),
        )
        network.setStatus(FakeNetworkMonitor.ONLINE)
        network.setStatus(FakeNetworkMonitor.ONLINE.copy(isMetered = true))
        assertEquals(PlayResult.NeedsMeteredConsent(1), gate.check(1, false, StartOrigin.UI))
    }

    @Test fun externalAskGrantsButExternalNeverBlocksEvenAfterGrant() {
        policy(StreamOnMetered.ASK)
        assertNull(gate.check(1, false, StartOrigin.EXTERNAL))
        assertNull(gate.check(2, false, StartOrigin.UI))
        policy(StreamOnMetered.NEVER)
        gate.grant()
        assertEquals(PlayResult.MeteredBlocked, gate.check(2, false, StartOrigin.EXTERNAL))
        assertTrue(gate.blockBeforeNext(false))
    }

    @Test fun downloadsPlayOfflineAndNeverGrantConsentForTheNextStream() {
        policy(StreamOnMetered.ASK)
        assertNull(gate.check(1, true, StartOrigin.EXTERNAL))
        assertTrue(gate.blockBeforeNext(false))
        assertFalse(gate.blockBeforeNext(true))
        network.setStatus(FakeNetworkMonitor.OFFLINE)
        assertNull(gate.check(1, true, StartOrigin.UI))
        assertEquals(PlayResult.Offline, gate.check(2, false, StartOrigin.EXTERNAL))
    }

    @Test fun defaultAllowStreamsWithoutConsent() {
        assertNull(gate.check(1, false, StartOrigin.UI))
        assertFalse(gate.blockBeforeNext(false))
    }

    @Test fun coroutineOriginDoesNotLeakIntoConcurrentUiStarts() =
        runBlocking {
            policy(StreamOnMetered.ASK)
            val ready = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val external =
                async {
                    gate.withOrigin(StartOrigin.EXTERNAL) {
                        ready.complete(Unit)
                        finish.await()
                        gate.check(media(), null)
                    }
                }
            ready.await()
            assertEquals(PlayResult.NeedsMeteredConsent(1), gate.check(media(), null))
            finish.complete(Unit)
            assertNull(external.await())
        }

    @Test fun arbiterKeepsBothPauseOwners() {
        var paused = false
        val arbiter = EndOfItemPauseArbiter { paused = it }
        arbiter.sleepTimerArmed = true
        arbiter.blockBeforeNext = true
        arbiter.sleepTimerArmed = false
        assertTrue(paused)
        arbiter.sleepTimerArmed = true
        arbiter.blockBeforeNext = false
        assertTrue(paused)
        arbiter.sleepTimerArmed = false
        assertFalse(paused)
    }
}
