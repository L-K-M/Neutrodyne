// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.domain.Departure
import ch.lkmc.neutrodyne.core.domain.PlaySessionStore
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.ScopeWriteResult
import ch.lkmc.neutrodyne.playback.core.StartRequest
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

    @Test fun pauseCancelsClientStartWhileServiceIsOpening() = pauseDuringStart(PauseSource.CANONICAL)

    @Test fun sessionPauseCancelsClientStartWhileServiceIsOpening() = pauseDuringStart(PauseSource.SESSION)

    private enum class PauseSource { CANONICAL, SESSION }

    private enum class StartPath { CLIENT, MEDIA_SESSION, SESSION_RESUME, SESSION_PLAY }

    private companion object {
        const val FALLBACK_GATE_TIMEOUT_SECONDS = 15L
        const val CURRENT_EPISODE_ID = 1L
        const val NEWER_EPISODE_ID = 2L
    }

    private fun pauseDuringStart(source: PauseSource) {
        val settingsReady = CompletableDeferred<Unit>()
        LifecycleHarness(app(), settingsReadGate = settingsReady).use { h ->
            h.seedCurrent()
            install(h)
            val component = ComponentName(app(), NeutrodynePlaybackService::class.java)
            val service = Robolectric.buildService(NeutrodynePlaybackService::class.java).create()
            shadowOf(app()).setComponentNameAndServiceForBindService(
                component,
                service.get().onBind(Intent("androidx.media3.session.MediaSessionService")),
            )
            val owner =
                object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this)
                }
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            val connection =
                PlayerConnection(owner.lifecycle) {
                    MediaController.Builder(app(), SessionToken(app(), component)).buildAsync()
                }
            val controller =
                PlaybackControllerImpl(
                    connection,
                    h.hub,
                    h.gate,
                    h.opener,
                    Provider { h.queues },
                    Provider { h.sessions },
                    h.clock,
                    h.scope,
                    Dispatchers.IO,
                )
            try {
                val pending = h.scope.async(start = CoroutineStart.UNDISPATCHED) { controller.play() }
                pumpUntil { connection.controller != null }
                assertFalse(pending.isCompleted)
                assertNull(h.hub.bridge)
                when (source) {
                    PauseSource.CANONICAL -> {
                        controller.pause()
                    }

                    PauseSource.SESSION -> {
                        service
                            .get()
                            .onGetSession(controllerInfo())!!
                            .player
                            .pause()
                    }
                }
                settingsReady.complete(Unit)
                val result = runCatching { h.run { pending.await() } }
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(
                    service
                        .get()
                        .onGetSession(controllerInfo())!!
                        .player.playWhenReady,
                )
                assertIs<CancellationException>(result.exceptionOrNull())
                assertTrue(pending.isCancelled)
            } finally {
                settingsReady.complete(Unit)
                owner.lifecycle.currentState = Lifecycle.State.DESTROYED
                service.destroy()
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

    @Test fun boundControllerAttachesAndPlaysOnceGateRecoveryOpensTheDatabase() {
        LifecycleHarness(app()).use { h ->
            h.storage = Storage.FAILED
            h.seedCurrent()
            install(h)
            val component = ComponentName(app(), NeutrodynePlaybackService::class.java)
            val service = Robolectric.buildService(NeutrodynePlaybackService::class.java).create()
            // Robolectric binds without resolving the component; hand the shadow the real binder.
            shadowOf(app()).setComponentNameAndServiceForBindService(
                component,
                service.get().onBind(Intent("androidx.media3.session.MediaSessionService")),
            )
            val owner =
                object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this)
                }
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            val connection =
                PlayerConnection(owner.lifecycle) {
                    MediaController
                        .Builder(app(), SessionToken(app(), component))
                        .buildAsync()
                }
            val controller =
                PlaybackControllerImpl(
                    connection,
                    h.hub,
                    h.gate,
                    h.opener,
                    Provider { h.queues },
                    Provider { h.sessions },
                    h.clock,
                    h.scope,
                    Dispatchers.IO,
                )
            try {
                pumpUntil { h.opener.openState.value is DatabaseOpenState.Failed }
                assertNull(h.hub.bridge)
                // The session stays available while its database open is failed.
                assertNotNull(h.run { connection.awaitController() })
                h.storage = Storage.READY
                // The start-up gate's retry owns the next open, as on the app's real failure path.
                h.run { h.opener.awaitOpen() }
                assertEquals(PlayResult.Started, h.run { controller.play() })
                pumpUntil { h.hub.bridge != null }
                val player = service.get().onGetSession(controllerInfo())!!.player
                pumpUntil { player.mediaItemCount == 1 && player.playWhenReady }
            } finally {
                connection.controller?.release()
                service.destroy()
            }
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

    @Test fun dismissWithoutACommandAnswerDegradesToTheDurableFallback() {
        // The service's open() hangs on the gated settings read, so the connected controller's
        // DISMISS command is never answered; the caller must still clear persisted state.
        val settingsRead = CompletableDeferred<Unit>()
        LifecycleHarness(app(), settingsReadGate = settingsRead).use { h ->
            h.seedCurrent()
            install(h)
            val component = ComponentName(app(), NeutrodynePlaybackService::class.java)
            val service = Robolectric.buildService(NeutrodynePlaybackService::class.java).create()
            // Robolectric binds without resolving the component; hand the shadow the real binder.
            shadowOf(app()).setComponentNameAndServiceForBindService(
                component,
                service.get().onBind(Intent("androidx.media3.session.MediaSessionService")),
            )
            val owner =
                object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this)
                }
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            val connection =
                PlayerConnection(owner.lifecycle) {
                    MediaController
                        .Builder(app(), SessionToken(app(), component))
                        .buildAsync()
                }
            val controller =
                PlaybackControllerImpl(
                    connection,
                    h.hub,
                    h.gate,
                    h.opener,
                    Provider { h.queues },
                    Provider { h.sessions },
                    h.clock,
                    h.scope,
                    Dispatchers.IO,
                )
            try {
                assertNotNull(h.run { connection.awaitController() })
                val dismissed = h.scope.async { controller.dismiss() }
                // idle() never advances the paused looper clock the command timeout is posted on.
                val looper = shadowOf(Looper.getMainLooper())
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (!dismissed.isCompleted) {
                    check(System.nanoTime() < deadline) { "Playback test timed out" }
                    looper.idleFor(1, TimeUnit.SECONDS)
                    Thread.sleep(5)
                }
                runBlocking { dismissed.await() }
                assertNull(h.store.queue.current)
            } finally {
                connection.controller?.release()
                service.destroy()
            }
        }
    }

    @Test fun aSupersededDurableDismissCannotClearANewerControllerStart() =
        assertNewerStartSurvivesDismiss(
            StartPath.CLIENT,
        )

    @Test fun aSupersededDurableDismissCannotClearANewerSessionStart() =
        assertNewerStartSurvivesDismiss(StartPath.MEDIA_SESSION)

    @Test fun aSupersededDurableDismissCannotClearANewerSessionResume() =
        assertNewerStartSurvivesDismiss(StartPath.SESSION_RESUME)

    @Test fun aSupersededDurableDismissCannotClearANewerLoadedSessionPlay() =
        assertNewerStartSurvivesDismiss(StartPath.SESSION_PLAY)

    private fun assertNewerStartSurvivesDismiss(path: StartPath) {
        LifecycleHarness(app()).use { h ->
            h.seedCurrent()
            h.lookup.items.value += NEWER_EPISODE_ID to media(id = NEWER_EPISODE_ID)
            install(h)
            val entered = CountDownLatch(1)
            val allowClear = CountDownLatch(1)
            val oldController =
                PlaybackControllerImpl(
                    h.connection,
                    h.hub,
                    h.gate,
                    h.opener,
                    Provider { h.queues },
                    Provider {
                        object : PlaySessionStore by h.sessions {
                            override suspend fun clearCurrent(
                                departure: Departure?,
                                now: Long,
                                allowed: () -> Boolean,
                            ): Long {
                                entered.countDown()
                                check(allowClear.await(FALLBACK_GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                                    "Test did not release the durable dismiss"
                                }
                                return h.sessions.clearCurrent(departure, now, allowed)
                            }
                        }
                    },
                    h.clock,
                    h.scope,
                    Dispatchers.IO,
                )
            val dismissed = h.scope.async { oldController.dismiss() }
            pumpUntil { entered.count == 0L }

            // The old dismiss already selected durable fallback; B uses the real binder path.
            val component = ComponentName(app(), NeutrodynePlaybackService::class.java)
            val service = Robolectric.buildService(NeutrodynePlaybackService::class.java).create()
            shadowOf(app()).setComponentNameAndServiceForBindService(
                component,
                service.get().onBind(Intent(MediaSessionService.SERVICE_INTERFACE)),
            )
            val owner =
                object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this)
                }
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            val connection =
                PlayerConnection(owner.lifecycle) {
                    MediaController.Builder(app(), SessionToken(app(), component)).buildAsync()
                }
            val newerController =
                PlaybackControllerImpl(
                    connection,
                    h.hub,
                    h.gate,
                    h.opener,
                    Provider { h.queues },
                    Provider { h.sessions },
                    h.clock,
                    h.scope,
                    Dispatchers.IO,
                )
            try {
                assertNotNull(h.run { connection.awaitController() })
                val player = service.get().onGetSession(controllerInfo())!!.player
                val expectedEpisodeId =
                    when (path) {
                        StartPath.CLIENT -> {
                            assertEquals(PlayResult.Started, h.run { newerController.playEpisode(NEWER_EPISODE_ID) })
                            NEWER_EPISODE_ID
                        }

                        StartPath.MEDIA_SESSION -> {
                            val live = checkNotNull(connection.controller)
                            live.setMediaItem(MediaItem.Builder().setMediaId("episode:$NEWER_EPISODE_ID").build())
                            live.play()
                            NEWER_EPISODE_ID
                        }

                        StartPath.SESSION_RESUME -> {
                            h.run {
                                val resumed = checkNotNull(h.hub.bridge).resume(StartOrigin.EXTERNAL)
                                player.setMediaItems(resumed.mediaItems, resumed.startIndex, resumed.startPositionMs)
                                player.prepare()
                                player.play()
                            }
                            CURRENT_EPISODE_ID
                        }

                        StartPath.SESSION_PLAY -> {
                            pumpUntil { player.episodeIdOrNull() == CURRENT_EPISODE_ID }
                            checkNotNull(connection.controller).play()
                            CURRENT_EPISODE_ID
                        }
                    }
                pumpUntil { player.episodeIdOrNull() == expectedEpisodeId && player.playWhenReady }
                allowClear.countDown()
                pumpUntil { dismissed.isCompleted }
                val failure = runBlocking { runCatching { dismissed.await() }.exceptionOrNull() }
                if (failure != null) assertIs<CancellationException>(failure)
                assertEquals(
                    expectedEpisodeId,
                    h.store.queue.current
                        ?.episodeId,
                )
                assertEquals(expectedEpisodeId, player.episodeIdOrNull())
            } finally {
                allowClear.countDown()
                connection.controller?.release()
                service.destroy()
            }
        }
    }

    @Test fun nonPositiveAndNonFiniteSpeedsAreRejectedBeforeReachingTheService() {
        LifecycleHarness(app()).use { h ->
            install(h)
            val component = ComponentName(app(), NeutrodynePlaybackService::class.java)
            val service = Robolectric.buildService(NeutrodynePlaybackService::class.java).create()
            // Robolectric binds without resolving the component; hand the shadow the real binder.
            shadowOf(app()).setComponentNameAndServiceForBindService(
                component,
                service.get().onBind(Intent("androidx.media3.session.MediaSessionService")),
            )
            val owner =
                object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this)
                }
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            val connection =
                PlayerConnection(owner.lifecycle) {
                    MediaController
                        .Builder(app(), SessionToken(app(), component))
                        .buildAsync()
                }
            val controller =
                PlaybackControllerImpl(
                    connection,
                    h.hub,
                    h.gate,
                    h.opener,
                    Provider { h.queues },
                    Provider { h.sessions },
                    h.clock,
                    h.scope,
                    Dispatchers.IO,
                )
            try {
                assertNotNull(h.run { connection.awaitController() })
                // With nothing playing the connected service could only answer NOTHING_PLAYING;
                // FAILED proves the value was rejected before the session command was dispatched.
                assertEquals(ScopeWriteResult.FAILED, h.run { controller.setSpeed(0f, SettingScope.GLOBAL) })
                assertEquals(ScopeWriteResult.FAILED, h.run { controller.setSpeed(-1f, SettingScope.GLOBAL) })
                assertEquals(ScopeWriteResult.FAILED, h.run { controller.setSpeed(Float.NaN, SettingScope.GLOBAL) })
            } finally {
                connection.controller?.release()
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
