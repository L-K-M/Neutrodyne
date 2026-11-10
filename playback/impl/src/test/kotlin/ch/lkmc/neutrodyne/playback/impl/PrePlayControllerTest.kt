// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.content.ComponentName
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import ch.lkmc.neutrodyne.core.domain.PrePlaySync
import ch.lkmc.neutrodyne.core.domain.StoredPosition
import ch.lkmc.neutrodyne.playback.api.PlayOrigin
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.core.SeekOrigin
import ch.lkmc.neutrodyne.playback.core.StartRequest
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.guava.await
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MS3 pull before play (10): the UI controller runs `beforeUiStart` before the session's
 * `PLAY_CONTEXT` planning — the pulled position wins over the stale local one — while a SYSTEM
 * origin, a service-side command, and a superseded intent never wait on or reach the hook.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = PlaybackTestApplication::class, sdk = [34])
class PrePlayControllerTest {
    private fun app(): PlaybackTestApplication = RuntimeEnvironment.getApplication() as PlaybackTestApplication

    private fun install(h: LifecycleHarness) {
        app().graph =
            object : PlaybackServiceInjector {
                override fun inject(service: NeutrodynePlaybackService) {
                    service.access = h.access
                }
            }
    }

    private fun bound(
        h: LifecycleHarness,
    ): Triple<ServiceController<NeutrodynePlaybackService>, PlayerConnection, PlaybackControllerImpl> {
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
                Provider { h.starter },
                h.clock,
                h.scope,
                Dispatchers.IO,
            )
        return Triple(service, connection, controller)
    }

    // Same raw-session Resume activation PlaybackServiceTest uses: it loads the seeded item
    // through the service path (never the UI hook) so a later play() hits AlreadyCurrent.
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

    @Test fun aUiResumeOnTheLoadedPausedItemTakesUpThePulledPosition() {
        LifecycleHarness(app()).use { h ->
            h.seedCurrent(positionMs = 12_000)
            val (service, connection, controller) = bound(h)
            try {
                // Activate the seeded item first so the resume deterministically hits the
                // AlreadyCurrent arm: the player holds episode 1 paused at its stored 12 s.
                h.run { activate(service.get(), h).await() }
                val player = service.get().onGetSession(controllerInfo())!!.player
                pumpUntil { player.mediaItemCount == 1 && player.currentPosition == 12_000L }
                player.pause()
                pumpUntil { !player.isPlaying }

                var pulls = 0
                h.prePlaySync =
                    PrePlaySync {
                        pulls++
                        h.positions.positions.value += 1L to StoredPosition(1, 45_000, h.clock.now(), null)
                    }
                assertEquals(PlayResult.Started, h.run { controller.play() })

                assertEquals(1, pulls)
                // An AlreadyCurrent resume plans no position: the paused-item pickup must
                // take up the stored 45 s over the loaded paused 12 s. The Media3 player
                // ticks forward from the seek, so bound the advance window.
                pumpUntil { player.currentPosition >= 45_000L }
                assertTrue(player.currentPosition < 50_000L, "picked-up position ${player.currentPosition}")
            } finally {
                connection.controller?.release()
                service.destroy()
            }
        }
    }

    @Test fun aSystemPlayNeverPulls() {
        LifecycleHarness(app()).use { h ->
            h.seedCurrent()
            var pulls = 0
            h.prePlaySync = PrePlaySync { pulls++ }
            val (service, connection, controller) = bound(h)
            try {
                assertEquals(PlayResult.Started, h.run { controller.play(PlayOrigin.SYSTEM) })
                assertEquals(0, pulls)
            } finally {
                connection.controller?.release()
                service.destroy()
            }
        }
    }

    @Test fun aSystemResumePicksUpTheObservedRowWithoutPulling() {
        LifecycleHarness(app()).use { h ->
            h.seedCurrent(positionMs = 12_000)
            val (service, connection, controller) = bound(h)
            try {
                h.run { activate(service.get(), h).await() }
                val player = service.get().onGetSession(controllerInfo())!!.player
                pumpUntil { player.mediaItemCount == 1 && player.currentPosition == 12_000L }
                player.pause()
                pumpUntil { !player.isPlaying }

                var pulls = 0
                h.prePlaySync = PrePlaySync { pulls++ }
                // The OS surface never pulls; the row it observes still wins the resume.
                h.positions.positions.value += 1L to StoredPosition(1, 45_000, h.clock.now(), null)
                assertEquals(PlayResult.Started, h.run { controller.play(PlayOrigin.SYSTEM) })

                assertEquals(0, pulls)
                pumpUntil { player.currentPosition >= 45_000L }
                assertTrue(player.currentPosition < 50_000L, "picked-up position ${player.currentPosition}")
            } finally {
                connection.controller?.release()
                service.destroy()
            }
        }
    }

    @Test fun aQueuedOwnFlushDuringTheReadNeverRewindsTheResume() {
        LifecycleHarness(app()).use { h ->
            h.seedCurrent(positionMs = 12_000)
            val (service, connection, controller) = bound(h)
            try {
                h.run { activate(service.get(), h).await() }
                val player = service.get().onGetSession(controllerInfo())!!.player
                pumpUntil { player.mediaItemCount == 1 && player.currentPosition == 12_000L }
                player.pause()
                pumpUntil { !player.isPlaying }

                // The resume's stored-row read snapshots the committed 12 s row but parks
                // before delivery; a queued own seek flush then commits 40 s and moves the
                // saver baseline. The stale emission must not drive the pickup seek.
                h.pickupReadEntered = CompletableDeferred()
                h.pickupReadGate = CompletableDeferred()
                val start = h.scope.async { controller.play(PlayOrigin.SYSTEM) }
                pumpUntil { h.pickupReadEntered.isCompleted }

                // The queued own flush commits 40 s and moves the saver baseline.
                h.run { h.saver.onSeek(40_000, null, SeekOrigin.USER) }
                pumpUntil {
                    h.positions.positions.value[1]
                        ?.positionMs == 40_000L
                }
                h.pickupReadGate?.complete(Unit)
                pumpUntil { start.isCompleted }
                assertEquals(PlayResult.Started, h.run { start.await() })

                // The parked 12 s snapshot must not drive the pickup: the baseline still
                // reflects the own 40 s write, so a row ~40.5 s is unchanged for the saver.
                assertNull(h.saver.pickupPositionMs(1, 40_500))
            } finally {
                connection.controller?.release()
                service.destroy()
            }
        }
    }

    @Test fun aNewerIntentDuringThePullAbandonsTheStart() {
        LifecycleHarness(app()).use { h ->
            h.seedCurrent()
            val pullEntered = CompletableDeferred<Unit>()
            val releasePull = CompletableDeferred<Unit>()
            h.prePlaySync =
                PrePlaySync {
                    pullEntered.complete(Unit)
                    releasePull.await()
                }
            val (service, connection, controller) = bound(h)
            try {
                val start = h.scope.async { controller.play() }
                pumpUntil { pullEntered.isCompleted }

                controller.pause() // newer intent while the UI wait is suspended
                releasePull.complete(Unit)

                assertFailsWith<CancellationException> { h.run { start.await() } }
                val player = service.get().onGetSession(controllerInfo())!!.player
                assertEquals(0, player.mediaItemCount)
                assertTrue(
                    h.store.calls.none {
                        it.operation ==
                            ch.lkmc.neutrodyne.core.testing.FakePlaySessionStore.Operation.START
                    },
                )
            } finally {
                connection.controller?.release()
                service.destroy()
            }
        }
    }

    @Test fun aServiceSessionCommandNeverPulls() {
        LifecycleHarness(app()).use { h ->
            h.seedCurrent()
            var pulls = 0
            h.prePlaySync = PrePlaySync { pulls++ }
            val (service, connection, controller) = bound(h)
            try {
                val media = h.run { connection.awaitController() }
                assertNotNull(media)
                // The session command path the OS/notification/Auto surfaces reach: it must
                // plan straight away — the pull-before-play hook is UI-side only.
                h.run {
                    media
                        .sendCustomCommand(
                            CustomCommands.command(CustomCommands.PLAY_CONTEXT),
                            CustomCommands.encode(StartRequest.Resume),
                        ).await()
                }
                assertEquals(0, pulls)
            } finally {
                connection.controller?.release()
                service.destroy()
            }
        }
    }
}
