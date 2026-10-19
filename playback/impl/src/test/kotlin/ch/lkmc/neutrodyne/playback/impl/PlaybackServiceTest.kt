// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.os.Bundle
import androidx.media3.session.MediaSession
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.core.model.settings.HardwareButtons
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.core.StartRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = PlaybackTestApplication::class, sdk = [34])
class PlaybackServiceTest {
    private fun app(): PlaybackTestApplication = RuntimeEnvironment.getApplication() as PlaybackTestApplication

    private fun install(h: LifecycleHarness) {
        app().graph =
            object : PlaybackServiceInjector {
                override fun inject(service: NeutrodynePlaybackService) {
                    service.access = h.access
                }
            }
    }

    @Test
    fun databaseReadinessDoesNotBypassTheStoredMeteredPolicy() {
        val firstSettings = CompletableDeferred<Unit>()
        LifecycleHarness(app(), settingsReadGate = firstSettings).use { h ->
            h.run {
                h.settings.set(
                    ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys.STREAM_ON_METERED,
                    ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered.NEVER,
                )
            }
            h.run {
                h.settings.set(PlaybackSettingKeys.SMART_RESUME, false)
                h.settings.set(PlaybackSettingKeys.HARDWARE_BUTTONS, HardwareButtons.SKIP)
            }
            val opening = h.scope.async { h.access.open() }
            h.run { h.opener.awaitOpen() }
            val early =
                h.run {
                    withContext(Dispatchers.Default) { withTimeoutOrNull(500) { opening.await() } }
                }
            assertNull(early)
            assertEquals(0, h.dbProviderReads)
            firstSettings.complete(Unit)
            h.run { opening.await() }
            assertFalse(h.prefs.current.value.smartResume)
            assertEquals(HardwareButtons.SKIP, h.prefs.current.value.hardwareButtons)
            h.network.setStatus(
                ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor.ONLINE
                    .copy(isMetered = true),
            )
            assertEquals(PlayResult.MeteredBlocked, h.gate.check(1, false, StartOrigin.EXTERNAL))
        }
    }

    @Test fun delayedDatabaseOpenDoesNotBlockSessionCreationOrResolvePortsOnMain() {
        LifecycleHarness(app(), delayed = true).use { h ->
            install(h)
            val service = Robolectric.buildService(NeutrodynePlaybackService::class.java).create()
            try {
                assertNotNull(service.get().onGetSession(controllerInfo()))
                pumpUntil { h.openStarted.count == 0L }
                assertEquals(0, h.dbProviderReads)
                assertNull(h.hub.bridge)
                h.allowOpen.countDown()
                pumpUntil { h.hub.bridge != null }
                assertTrue(h.dbProviderReads > 0)
                assertEquals(0, h.dbProviderReadsOnMain.get())
                assertEquals(
                    0,
                    service
                        .get()
                        .onGetSession(controllerInfo())!!
                        .player.mediaItemCount,
                )
            } finally {
                service.destroy()
            }
            assertNull(h.hub.bridge)
        }
    }

    @Test fun destructionWhileOpeningNeverAttachesOrResolvesDatabasePorts() {
        LifecycleHarness(app(), delayed = true).use { h ->
            install(h)
            val service = Robolectric.buildService(NeutrodynePlaybackService::class.java).create()
            pumpUntil { h.openStarted.count == 0L }
            service.destroy()
            h.allowOpen.countDown()
            // Cancelling the only opener leaves Pending; the process startup lane retries it.
            h.run { h.opener.awaitOpen() }
            assertEquals(0, h.dbProviderReads)
            assertNull(h.hub.bridge)
        }
    }

    @Test fun canonicalPauseWorksWithoutConnectionAndDismissDiffersFromStop() {
        LifecycleHarness(app()).use { h ->
            h.seedCurrent()
            install(h)
            val service = Robolectric.buildService(NeutrodynePlaybackService::class.java).create()
            try {
                pumpUntil { h.hub.bridge != null }
                val bridge = assertNotNull(h.hub.bridge)
                val player = service.get().onGetSession(controllerInfo())!!.player
                pumpUntil { player.mediaItemCount == 1 }
                assertNull(h.connection.controller)
                h.controller.pause()
                assertFalse(player.playWhenReady)
                h.run { bridge.stop() }
                assertEquals(
                    1,
                    h.store.queue.current
                        ?.episodeId,
                )
                h.run { bridge.dismiss() }
                assertNull(h.store.queue.current)
                assertEquals(0, player.mediaItemCount)
            } finally {
                service.destroy()
            }
        }
    }

    @Test fun sessionAndUiPausePathsUseElapsedSmartResumeWithoutReloading() {
        LifecycleHarness(app()).use { h ->
            h.seedCurrent()
            install(h)
            val service = Robolectric.buildService(NeutrodynePlaybackService::class.java).create()
            try {
                pumpUntil { h.hub.bridge != null }
                val player = service.get().onGetSession(controllerInfo())!!.player
                pumpUntil { player.mediaItemCount == 1 && player.currentPosition == 12_000L }
                player.play()
                pumpUntil { player.playWhenReady }
                player.pause()
                h.clock.elapsedMs += 300_000
                player.play()
                pumpUntil { player.playWhenReady }
                assertEquals(12_000L, player.currentPosition)
                h.controller.pause()
                h.clock.elapsedMs += 300_001
                h.clock.nowMs -= 1_000_000
                player.play()
                pumpUntil { player.currentPosition == 7_000L }
                assertEquals(1, player.mediaItemCount)
                player.pause()
                h.run { h.settings.set(PlaybackSettingKeys.SMART_RESUME, false) }
                pumpUntil { !h.prefs.current.value.smartResume }
                h.clock.elapsedMs += 300_001
                player.play()
                pumpUntil { player.playWhenReady }
                assertEquals(7_000L, player.currentPosition)
                h.run { h.settings.set(PlaybackSettingKeys.SMART_RESUME, true) }
                pumpUntil { h.prefs.current.value.smartResume }
                player.pause()
                h.clock.elapsedMs += 300_001
                h.run { checkNotNull(h.hub.bridge).start(StartRequest.Episode(1, 754_000), StartOrigin.UI) }
                player.play()
                pumpUntil { player.playWhenReady }
                assertEquals(754_000L, player.currentPosition)
            } finally {
                service.destroy()
            }
        }
    }

    @Test fun testTimeoutHookIsNormallyInertAndCanBeArmedBeforeCreation() {
        assertNull(PlaybackTestHooks.foregroundServiceTimeoutMs)
        LifecycleHarness(app()).use { h ->
            install(h)
            PlaybackTestHooks.foregroundServiceTimeoutMs = 100
            val service = Robolectric.buildService(NeutrodynePlaybackService::class.java)
            try {
                service.create()
                assertNotNull(service.get().onGetSession(controllerInfo()))
            } finally {
                service.destroy()
                PlaybackTestHooks.foregroundServiceTimeoutMs = null
            }
        }
    }
}

internal fun controllerInfo(
    packageName: String = "synthetic.controller",
    trusted: Boolean = true,
): MediaSession.ControllerInfo =
    MediaSession.ControllerInfo.createTestOnlyControllerInfo(packageName, 1, 12345, 0, 0, trusted, Bundle.EMPTY, true)
