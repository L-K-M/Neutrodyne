// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.core.StartRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

    private fun activate(
        service: NeutrodynePlaybackService,
        h: LifecycleHarness,
    ): kotlinx.coroutines.Deferred<Unit> =
        h.scope.async {
            val session = service.onGetSession(controllerInfo())!!
            val controller = MediaController.Builder(h.app, session.token).buildAsync().await()
            try {
                controller
                    .sendCustomCommand(
                        CustomCommands.command(CustomCommands.PLAY_CONTEXT),
                        CustomCommands.encode(StartRequest.Resume),
                    ).await()
                if (h.store.queue.current != null) {
                    val loaded = CompletableDeferred<Unit>()
                    val listener =
                        object : androidx.media3.common.Player.Listener {
                            override fun onTimelineChanged(
                                timeline: androidx.media3.common.Timeline,
                                reason: Int,
                            ) {
                                if (session.player.mediaItemCount > 0) loaded.complete(Unit)
                            }
                        }
                    session.player.addListener(listener)
                    try {
                        controller.play()
                        if (session.player.mediaItemCount == 0) loaded.await()
                    } finally {
                        session.player.removeListener(listener)
                    }
                    controller.pause()
                }
            } finally {
                controller.release()
            }
        }

    @Test fun metadataOnlyServiceStartupDoesNotProjectPinPrepareOrRequestNetwork() {
        LifecycleHarness(app()).use { h ->
            val server = mockwebserver3.MockWebServer().apply { start() }
            h.seedCurrent()
            h.lookup.items.value += 1L to media(url = server.url("/episode.mp3").toString())
            install(h)
            val service = Robolectric.buildService(NeutrodynePlaybackService::class.java).create()
            try {
                val session = service.get().onGetSession(controllerInfo())!!
                val browser =
                    h.run {
                        androidx.media3.session.MediaBrowser
                            .Builder(
                                h.app,
                                session.token,
                            ).buildAsync()
                            .await()
                    }
                try {
                    h.run { browser.getLibraryRoot(null).await() }
                    val recent =
                        h.run {
                            RecentRootProbe
                                .children(
                                    session,
                                    controllerInfo("com.android.systemui"),
                                ).await()
                        }
                    assertEquals(0, recent.resultCode)
                    assertEquals("episode:1", recent.value!!.single().mediaId)
                    assertEquals(0, session.player.mediaItemCount)
                    assertFalse(session.player.playWhenReady)
                    assertNull(h.hub.bridge)
                    assertNull(h.index[1])
                    assertNull(h.resolver.streamKind(1))
                    assertTrue(h.store.calls.isEmpty())
                    assertEquals(0, server.requestCount)
                    assertEquals(androidx.media3.common.Player.STATE_IDLE, session.player.playbackState)
                } finally {
                    browser.release()
                }
            } finally {
                service.destroy()
                server.close()
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
                assertEquals(1, h.openStarted.count)
                activate(service.get(), h)
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
            activate(service.get(), h)
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
                h.run { activate(service.get(), h).await() }
                pumpUntil { h.hub.bridge != null }
                val bridge = assertNotNull(h.hub.bridge)
                val player = service.get().onGetSession(controllerInfo())!!.player
                pumpUntil { player.mediaItemCount == 1 }
                assertNull(h.connection.controller)
                h.controller.pause()
                pumpUntil { !player.playWhenReady }
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
