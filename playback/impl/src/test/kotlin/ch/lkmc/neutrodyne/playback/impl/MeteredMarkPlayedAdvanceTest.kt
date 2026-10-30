// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.os.Build
import android.os.Looper
import ch.lkmc.neutrodyne.core.model.QueueItem
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePlaySessionStore
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.UnplayableReason
import ch.lkmc.neutrodyne.playback.core.StartRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.time.Duration.Companion.minutes

/** A refused mark-played advance must resume at the queued episode, not replay the marked one. */
@RunWith(RobolectricTestRunner::class)
@Config(application = PlaybackTestApplication::class, sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class MeteredMarkPlayedAdvanceTest {
    @Test fun aMeteredRefusalDoesNotConsumeTheMarkPlayedAdvance() =
        withMeteredAdvance { fixture ->
            fixture.markCurrentPlayedWhileMetered()
            fixture.assertBlockedAdvanceRefused()

            fixture.h.network.setStatus(FakeNetworkMonitor.ONLINE)
            pumpUntil { !fixture.player.pauseAtEndOfMediaItems }
            fixture.resumeCanonically()
            fixture.assertAdvancedToNextExactlyOnce()
        }

    @Test fun aMeteredConsentGrantStillCompletesTheRefusedAdvance() =
        withMeteredAdvance { fixture ->
            fixture.markCurrentPlayedWhileMetered()
            fixture.assertBlockedAdvanceRefused()

            fixture.h.controller.grantMeteredStreaming()
            fixture.resumeCanonically()
            fixture.assertAdvancedToNextExactlyOnce()
        }

    @Test fun aNewlyLocalNextItemCanResumeWhileTheMarkedCurrentIsOffline() =
        withMeteredAdvance { fixture ->
            fixture.markCurrentPlayedWhileMetered()
            fixture.assertBlockedAdvanceRefused()

            // This changes admission, not decoding: the next item became local after refusal.
            fixture.h.local.files[NEXT] = "file:///next.mp3"
            fixture.h.network.setStatus(FakeNetworkMonitor.OFFLINE)
            pumpUntil { !fixture.player.pauseAtEndOfMediaItems }
            fixture.resumeCanonically()
            fixture.assertAdvancedToNextExactlyOnce()
        }

    @Test fun anExternalLoadedPlayerResumeAdvancesWithoutAStandingGrant() =
        withMeteredAdvance { fixture ->
            fixture.markCurrentPlayedWhileMetered()
            fixture.assertBlockedAdvanceRefused()

            assertTrue(fixture.h.run { fixture.bridge.beforePlay(StartOrigin.EXTERNAL) })
            fixture.playAndPump()
            fixture.assertAdvancedToNextExactlyOnce()
            assertTrue("external resume granted later streams", fixture.h.gate.blockBeforeNext(local = false))
            assertNull(fixture.h.gate.pendingConsent.value)
        }

    @Test fun aStillBlockedResumeKeepsTheAdvanceForALaterGrant() =
        withMeteredAdvance { fixture ->
            fixture.markCurrentPlayedWhileMetered()
            fixture.assertBlockedAdvanceRefused()

            assertEquals(
                PlayResult.NeedsMeteredConsent(NEXT),
                fixture.h.run { fixture.bridge.start(StartRequest.Resume, StartOrigin.UI) },
            )
            fixture.assertBlockedAdvanceRefused()
            fixture.h.controller.grantMeteredStreaming()
            fixture.resumeCanonically()
            fixture.assertAdvancedToNextExactlyOnce()
        }

    @Test fun unmarkingTheDepartureCancelsTheDeferredAdvance() =
        withMeteredAdvance { fixture ->
            fixture.markCurrentPlayedWhileMetered()
            fixture.assertBlockedAdvanceRefused()

            fixture.h.run { fixture.h.positions.markUnplayedForReplay(CURRENT, fixture.h.clock.now()) }
            fixture.h.lookup.items.value = fixture.h.lookup.items.value +
                (CURRENT to checkNotNull(fixture.h.lookup.items.value[CURRENT]).copy(playedAt = null))
            fixture.h.controller.grantMeteredStreaming()
            fixture.resumeCanonically(CURRENT)
            fixture.assertStayedOnCurrent()
        }

    @Test fun anExplicitCurrentEpisodeStartSupersedesTheBlockedAdvance() =
        withMeteredAdvance { fixture ->
            fixture.markCurrentPlayedWhileMetered()
            fixture.assertBlockedAdvanceRefused()
            fixture.h.controller.grantMeteredStreaming()

            assertEquals(
                PlayResult.Started,
                fixture.h.run { fixture.bridge.start(StartRequest.Episode(CURRENT, 0), StartOrigin.UI) },
            )
            assertTrue(fixture.h.run { fixture.bridge.beforePlay(StartOrigin.UI) })
            fixture.playAndPump(CURRENT)
            fixture.assertStayedOnCurrent()
        }

    @Test fun aFailedMarkLookupKeepsTheAdvanceForRetry() =
        withMeteredAdvance { fixture ->
            fixture.markCurrentPlayedWhileMetered()
            fixture.assertBlockedAdvanceRefused()
            fixture.h.controller.grantMeteredStreaming()

            fixture.h.lookup.failure = IOException("lookup unavailable")
            assertEquals(
                PlayResult.ServiceUnavailable,
                fixture.h.run { fixture.bridge.start(StartRequest.Resume, StartOrigin.UI) },
            )
            fixture.h.lookup.failure = null
            fixture.resumeCanonically()
            fixture.assertAdvancedToNextExactlyOnce()
        }

    @Test fun aReplacementDuringMarkReadCannotAdvanceTheOldSession() =
        withMeteredAdvance { fixture ->
            fixture.markCurrentPlayedWhileMetered()
            fixture.assertBlockedAdvanceRefused()
            fixture.h.controller.grantMeteredStreaming()
            fixture.h.lookup.items.value += OTHER to media(id = OTHER)
            val entered = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            fixture.h.lookup.beforeGet = { id ->
                if (id == CURRENT) {
                    entered.complete(Unit)
                    proceed.await()
                }
            }
            val attempt = fixture.h.scope.async { fixture.bridge.start(StartRequest.Resume, StartOrigin.UI) }
            fixture.h.run { entered.await() }
            fixture.h.publish(
                fixture.h.store.queue.copy(
                    generation = fixture.h.store.queue.generation + 1,
                    current = QueueItem(OTHER, QueueOrigin.CURRENT),
                ),
            )
            proceed.complete(Unit)
            assertEquals(PlayResult.ServiceUnavailable, fixture.h.run { attempt.await() })
            pumpUntil { fixture.player.episodeIdOrNull() == OTHER }
            assertFalse("replacement was auto-played", fixture.player.playWhenReady)
            assertTrue(
                fixture.h.store.calls
                    .none { it.operation == FakePlaySessionStore.Operation.TRANSITION },
            )
        }

    @Test fun aReplacementAfterTheDurableReadCannotAdvanceTheOldSession() =
        withMeteredAdvance { fixture ->
            fixture.markCurrentPlayedWhileMetered()
            fixture.assertBlockedAdvanceRefused()
            fixture.h.controller.grantMeteredStreaming()
            fixture.h.lookup.items.value += OTHER to media(id = OTHER)
            fixture.h.store.afterVirtualQueueRead = { snapshot ->
                if (currentCoroutineContext()[CoroutineName]?.name == RESUME_TEST_JOB) {
                    fixture.h.store.afterVirtualQueueRead = null
                    assertEquals(CURRENT, snapshot.current?.episodeId)
                    withContext(Dispatchers.Main.immediate) {
                        fixture.h.publish(
                            snapshot.copy(
                                generation = snapshot.generation + 1,
                                current = QueueItem(OTHER, QueueOrigin.CURRENT),
                            ),
                        )
                        // Deliver the source change before the old IO snapshot returns to main.
                        yield()
                    }
                }
            }
            val attempt =
                fixture.h.scope.async(CoroutineName(RESUME_TEST_JOB)) {
                    fixture.bridge.start(StartRequest.Resume, StartOrigin.UI)
                }
            assertEquals(PlayResult.ServiceUnavailable, fixture.h.run { attempt.await() })
            pumpUntil { fixture.player.episodeIdOrNull() == OTHER }
            assertFalse("replacement was auto-played", fixture.player.playWhenReady)
            assertTrue(
                fixture.h.store.calls
                    .none { it.operation == FakePlaySessionStore.Operation.TRANSITION },
            )
        }

    @Test fun aNextItemRemovedDuringAdmissionCannotBePlayed() =
        withMeteredAdvance { fixture ->
            fixture.markCurrentPlayedWhileMetered()
            fixture.assertBlockedAdvanceRefused()
            fixture.h.controller.grantMeteredStreaming()
            fixture.h.lookup.beforeGet = { id ->
                if (id == CURRENT) fixture.h.lookup.items.value -= NEXT
            }
            assertEquals(
                PlayResult.NotPlayable(NEXT, UnplayableReason.NoMedia),
                fixture.h.run { fixture.bridge.start(StartRequest.Resume, StartOrigin.UI) },
            )
            fixture.assertBlockedAdvanceRefused()
        }

    @Test fun removingTheNextItemInvalidatesTheDeferredAdvance() =
        withMeteredAdvance { fixture ->
            fixture.markCurrentPlayedWhileMetered()
            fixture.assertBlockedAdvanceRefused()
            fixture.h.clock.advanceBy(1.minutes)
            fixture.h.publish(
                fixture.h.store.queue
                    .copy(generation = fixture.h.store.queue.generation + 1, upNext = emptyList()),
            )
            pumpUntil { fixture.player.mediaItemCount == 1 }
            fixture.h.controller.grantMeteredStreaming()
            fixture.resumeCanonically(CURRENT)
            fixture.assertStayedOnCurrent()
        }

    private fun withMeteredAdvance(block: (Fixture) -> Unit) {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h -> meteredAdvanceFixture(h).use(block) }
    }

    /** Observe the real guard's pause without replacing its network policy. */
    private fun meteredAdvanceFixture(h: LifecycleHarness): Fixture {
        h.seedCurrent()
        h.lookup.items.value += NEXT to media(id = NEXT)
        h.publish(h.store.queue.copy(upNext = listOf(QueueItem(NEXT, QueueOrigin.UP_NEXT))))
        h.run { h.settings.set(PlaybackSettingKeys.STREAM_ON_METERED, StreamOnMetered.ASK) }
        val player = h.playerFactory.create()
        var pauses = 0
        val bridge =
            AndroidServiceBridge(
                player,
                h.run { h.access.open() },
                h.access,
                h.scope,
                { true },
                {},
                {
                    pauses++
                    player.pause()
                },
            )
        bridge.attach()
        pumpUntil { player.mediaItemCount == 2 && player.episodeIdOrNull() == CURRENT }
        player.play()
        h.network.setStatus(FakeNetworkMonitor.ONLINE.copy(isMetered = true))
        pumpUntil { player.pauseAtEndOfMediaItems }
        return Fixture(h, player, bridge) { pauses }
    }

    private class Fixture(
        val h: LifecycleHarness,
        val player: androidx.media3.exoplayer.ExoPlayer,
        val bridge: AndroidServiceBridge,
        val pauses: () -> Int,
    ) : AutoCloseable {
        /** The repository mark-played write plus the media read the transaction commits in Room. */
        fun markCurrentPlayedWhileMetered() {
            h.clock.advanceBy(1.minutes)
            val now = h.clock.now()
            h.run { h.positions.markPlayed(CURRENT, now) }
            h.lookup.items.value =
                h.lookup.items.value +
                (CURRENT to checkNotNull(h.lookup.items.value[CURRENT]).copy(playedAt = now))
            pumpUntil { pauses() > 0 }
        }

        /** The metered guard must hold: the blocked next item never becomes current or plays. */
        fun assertBlockedAdvanceRefused() {
            assertEquals(CURRENT, player.episodeIdOrNull())
            assertEquals("episode:$CURRENT", player.currentMediaItem?.mediaId)
            assertFalse("the refused automatic advance did not pause the player", player.playWhenReady)
            assertTrue(
                "the automatic advance reached the blocked next item",
                h.store.calls.none { it.operation == FakePlaySessionStore.Operation.TRANSITION },
            )
        }

        /** The canonical resume: `controller.play()` → `start(Resume)` → `beforePlay` → `play()`. */
        fun resumeCanonically(expectedEpisodeId: Long = NEXT) {
            assertEquals(PlayResult.Started, h.run { bridge.start(StartRequest.Resume, StartOrigin.UI) })
            assertTrue("the resume gate refused playback", h.run { bridge.beforePlay(StartOrigin.UI) })
            playAndPump(expectedEpisodeId)
        }

        fun playAndPump(expectedEpisodeId: Long = NEXT) {
            player.play()
            for (attempt in 0 until RESUME_POLL_ATTEMPTS) {
                shadowOf(Looper.getMainLooper()).idle()
                if (player.episodeIdOrNull() == expectedEpisodeId) break
                Thread.sleep(RESUME_POLL_DELAY_MS)
            }
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun assertStayedOnCurrent() {
            assertEquals(CURRENT, player.episodeIdOrNull())
            assertTrue(h.store.calls.none { it.operation == FakePlaySessionStore.Operation.TRANSITION })
        }

        fun assertAdvancedToNextExactlyOnce() {
            assertEquals(
                "resume did not complete the refused advance to the queued item",
                "episode:$NEXT",
                player.currentMediaItem?.mediaId,
            )
            assertEquals(
                "the advance did not commit exactly once",
                1,
                h.store.calls.count {
                    it.operation == FakePlaySessionStore.Operation.TRANSITION && it.arrivalId == NEXT
                },
            )
            assertNotNull(
                "the skipped current lost its played mark",
                h.lookup.items.value[CURRENT]
                    ?.playedAt,
            )
        }

        override fun close() {
            bridge.detach()
            player.release()
        }
    }

    private companion object {
        const val CURRENT = 1L
        const val NEXT = 2L
        const val OTHER = 3L
        const val RESUME_POLL_ATTEMPTS = 200
        const val RESUME_POLL_DELAY_MS = 5L
        const val RESUME_TEST_JOB = "deferred-resume"
    }
}
