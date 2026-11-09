// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.domain.StoredPosition
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.EffectivePlayback
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.core.model.SleepTimerMode
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.VirtualQueue
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import ch.lkmc.neutrodyne.playback.api.PositionSnapshot
import ch.lkmc.neutrodyne.playback.api.ScopeWriteResult
import ch.lkmc.neutrodyne.playback.core.StartRequest
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PlaybackStateHubTest {
    @Test fun observingDurableSessionWaitsForOpenAndNeverConnects() {
        LifecycleHarness(RuntimeEnvironment.getApplication(), delayed = true).use { h ->
            h.seedCurrent()
            assertNull(h.hub.nowPlaying.value)
            assertEquals(0, h.dbProviderReads)
            val job = h.scope.launch { h.hub.nowPlaying.collect() }
            pumpUntil { h.openStarted.count == 0L }
            assertEquals(0, h.dbProviderReads)
            assertNull(h.connection.controller)
            h.allowOpen.countDown()
            pumpUntil { h.hub.nowPlaying.value != null }
            val item = assertNotNull(h.hub.nowPlaying.value)
            assertEquals(PlayerPhase.NOT_LOADED, item.phase)
            assertFalse(item.isPlaying)
            assertEquals(12_000L, item.position.positionMs)
            h.positions.positions.value += 1L to StoredPosition(1, 25_000, h.clock.now(), null)
            pumpUntil {
                h.hub.nowPlaying.value
                    ?.position
                    ?.positionMs == 25_000L
            }
            assertNull(h.connection.controller)
            job.cancel()
        }
    }

    /**
     * [NowPlaying.loadId] (08 Sync notices): a metadata refresh of the durable item keeps the
     * occurrence, while unload→reload of the same episode mints a fresh identity.
     */
    @Test fun durableLoadIdentityMintsFreshAcrossUnloadAndReload() {
        LifecycleHarness(RuntimeEnvironment.getApplication(), delayed = true).use { h ->
            h.seedCurrent()
            val job = h.scope.launch { h.hub.nowPlaying.collect() }
            h.allowOpen.countDown()
            pumpUntil { h.hub.nowPlaying.value != null }
            val first = assertNotNull(h.hub.nowPlaying.value).loadId
            assertNotEquals(0L, first)
            h.positions.positions.value += 1L to StoredPosition(1, 9_000, h.clock.now(), null)
            pumpUntil {
                h.hub.nowPlaying.value
                    ?.position
                    ?.positionMs == 9_000L
            }
            assertEquals(
                first,
                h.hub.nowPlaying.value!!
                    .loadId,
            )
            h.publish(VirtualQueue(2, null, emptyList(), emptyList()))
            pumpUntil { h.hub.nowPlaying.value == null }
            h.seedCurrent()
            pumpUntil { h.hub.nowPlaying.value != null }
            assertNotEquals(
                first,
                h.hub.nowPlaying.value!!
                    .loadId,
            )
            job.cancel()
        }
    }

    /** A live item detached without a durable change keeps its occurrence identity: the
     * remembered loadId stamps the durable fallback instead of a fresh mint. */
    @Test fun liveToDurableHandoverKeepsTheLoadIdentity() {
        LifecycleHarness(RuntimeEnvironment.getApplication(), delayed = true).use { h ->
            h.seedCurrent()
            val bridge = FakeBridge(liveNow(1, 555L))
            val job = h.scope.launch { h.hub.nowPlaying.collect() }
            h.allowOpen.countDown()
            h.hub.attach(bridge)
            pumpUntil {
                h.hub.nowPlaying.value
                    ?.loadId == 555L
            }
            h.hub.detach(bridge)
            pumpUntil {
                h.hub.nowPlaying.value
                    ?.phase == PlayerPhase.NOT_LOADED
            }
            assertEquals(
                555L,
                h.hub.nowPlaying.value!!
                    .loadId,
            )
            job.cancel()
        }
    }

    /** The merged StateFlow lags a synchronous bridge commit; post-time stamping must not. */
    @Test fun currentNowPlayingFollowsTheLiveCommitBeforeTheMergedViewPumps() {
        LifecycleHarness(RuntimeEnvironment.getApplication(), delayed = true).use { h ->
            h.seedCurrent()
            h.allowOpen.countDown()
            val bridge = FakeBridge(liveNow(1, 555L))
            val job = h.scope.launch { h.hub.nowPlaying.collect() }
            h.hub.attach(bridge)
            pumpUntil {
                h.hub.nowPlaying.value
                    ?.episodeId == 1L
            }
            bridge.now.value = liveNow(2, 777L)
            assertEquals(2L, h.hub.currentNowPlaying?.episodeId)
            assertEquals(777L, h.hub.currentNowPlaying?.loadId)
            pumpUntil {
                h.hub.nowPlaying.value
                    ?.episodeId == 2L
            }
            job.cancel()
        }
    }

    @Test fun noSubscribersDoNotDereferenceDatabaseProviders() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            assertEquals(0, h.dbProviderReads)
            assertFalse(h.opener.openState.value is DatabaseOpenState.Opened)
            assertNull(h.hub.nowPlaying.value)
            assertNull(h.hub.effectivePlayback.value)
        }
    }
}

private fun liveNow(
    id: Long,
    loadId: Long,
) = NowPlaying(
    episodeId = id,
    podcastId = 2,
    title = "Episode $id",
    podcastTitle = "Podcast",
    artwork = ArtworkRef("art", null, 0),
    sourceType = SourceType.RSS,
    isVideo = false,
    phase = PlayerPhase.READY,
    isPlaying = true,
    playWhenReady = true,
    position = PositionSnapshot(id, 0, 0, 60_000, 1f, true, 0),
    hasNext = false,
    stream = null,
    issue = null,
    context = null,
    loadId = loadId,
)

/** A [ServiceBridge] whose current item tests can commit synchronously. */
private class FakeBridge(
    item: NowPlaying?,
) : ServiceBridge {
    val now = MutableStateFlow(item)
    override val nowPlaying: StateFlow<NowPlaying?> = now
    override val effectivePlayback = MutableStateFlow<EffectivePlayback?>(null)
    override val isPlaying = false

    override fun setSleepTimer(mode: SleepTimerMode) {}

    override fun extendSleepTimer(minutes: Int) {}

    override fun pause() {}

    override suspend fun pauseBeforeUnsubscribe(podcastIds: List<Long>) {}

    override suspend fun start(
        request: StartRequest,
        origin: StartOrigin,
    ): PlayResult = error("unused")

    override suspend fun setMediaItems(
        request: StartRequest,
        origin: StartOrigin,
    ): androidx.media3.session.MediaSession.MediaItemsWithStartPosition = error("unused")

    override suspend fun resume(origin: StartOrigin): androidx.media3.session.MediaSession.MediaItemsWithStartPosition =
        error("unused")

    override suspend fun beforePlay(origin: StartOrigin) = true

    override suspend fun previous(origin: StartOrigin) {}

    override suspend fun stop() {}

    override suspend fun dismiss() {}

    override suspend fun setSpeed(
        speed: Float,
        scope: SettingScope,
    ): ScopeWriteResult = error("unused")

    override suspend fun setSkipSilence(
        enabled: Boolean,
        scope: SettingScope,
    ): ScopeWriteResult = error("unused")

    override suspend fun cycleSpeed(): ScopeWriteResult = error("unused")
}
