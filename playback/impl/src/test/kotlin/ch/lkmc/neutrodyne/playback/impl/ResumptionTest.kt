// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.common.Player
import ch.lkmc.neutrodyne.core.model.QueueItem
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.VirtualQueue
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.PlaybackIssue
import ch.lkmc.neutrodyne.playback.core.StartRequest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = PlaybackTestApplication::class, sdk = [34])
class ResumptionTest {
    @Test fun emptyUiStartWaitsForTrueResumptionAndReturnsOneCanonicalWindow() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            h.lookup.items.value = mapOf(1L to media())
            h.publish(VirtualQueue(1, null, listOf(QueueItem(1, QueueOrigin.UP_NEXT)), emptyList()))
            val player = h.playerFactory.create()
            val ports = h.run { h.access.open() }
            val bridge = AndroidServiceBridge(player, ports, h.access, h.scope, { true }, {}, player::pause)
            bridge.attach()
            try {
                assertEquals(PlayResult.Started, h.run { bridge.start(StartRequest.Episode(1), StartOrigin.UI) })
                assertEquals(0, player.mediaItemCount)
                assertEquals(Player.STATE_IDLE, player.playbackState)
                val provider = ResumptionProvider { bridge }
                val result = h.run { provider.resume(true, StartOrigin.UI) }
                assertEquals(listOf("episode:1"), result.mediaItems.map { it.mediaId })
                assertEquals(0, result.startIndex)
                assertEquals(0, player.mediaItemCount)
                assertNotNull(h.index[1])
                player.setMediaItems(result.mediaItems, result.startIndex, result.startPositionMs)
                assertEquals(1, player.mediaItemCount)
                assertFalse(player.playWhenReady)
                assertEquals(
                    1,
                    h.store.calls.count {
                        it.operation ==
                            ch.lkmc.neutrodyne.core.testing.FakePlaySessionStore.Operation.START
                    },
                )
            } finally {
                bridge.detach()
                player.release()
            }
        }
    }

    @Test
    fun pendingTrueResumptionPreventsTheInitialPausedProjectionFromSettingTwice() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            h.seedCurrent()
            val player = h.playerFactory.create()
            val ports = h.run { h.access.open() }
            var sets = 0
            player.addListener(
                object : Player.Listener {
                    override fun onTimelineChanged(
                        timeline: androidx.media3.common.Timeline,
                        reason: Int,
                    ) {
                        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) sets++
                    }
                },
            )
            val bridge = AndroidServiceBridge(player, ports, h.access, h.scope, { true }, {}, player::pause)
            bridge.attach(hasPendingStart = true)
            try {
                val window = h.run { bridge.resume(StartOrigin.EXTERNAL) }
                assertEquals(0, player.mediaItemCount)
                assertEquals(0, sets)
                assertEquals(12_000L, window.startPositionMs)
                player.setMediaItems(window.mediaItems, window.startIndex, window.startPositionMs)
                assertEquals(1, sets)
                assertTrue(h.store.calls.isEmpty())
            } finally {
                bridge.detach()
                player.release()
            }
        }
    }

    @Test
    fun aDownloadedCurrentCannotBypassConsentForTheNextStream() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            h.seedCurrent()
            h.local.files[1] = "file:///synthetic-download.mp3"
            h.lookup.items.value += 2L to media(id = 2)
            h.publish(h.store.queue.copy(upNext = listOf(QueueItem(2, QueueOrigin.UP_NEXT))))
            h.network.setStatus(
                FakeNetworkMonitor.ONLINE
                    .copy(isMetered = true),
            )
            h.run {
                h.settings.set(
                    PlaybackSettingKeys.STREAM_ON_METERED,
                    StreamOnMetered.ASK,
                )
            }
            val player = h.playerFactory.create()
            val bridge =
                AndroidServiceBridge(player, h.run { h.access.open() }, h.access, h.scope, { true }, {}, player::pause)
            bridge.attach()
            try {
                pumpUntil { player.mediaItemCount == 2 }
                assertTrue(player.pauseAtEndOfMediaItems)
                bridge.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
                assertEquals(
                    PlayResult.NeedsMeteredConsent(2),
                    h.run { bridge.start(StartRequest.Resume, StartOrigin.UI) },
                )
                assertFalse(player.playWhenReady)
                h.controller.grantMeteredStreaming()
                assertTrue(h.run { bridge.beforePlay(StartOrigin.UI) })
                assertFalse(player.pauseAtEndOfMediaItems)
                h.run {
                    h.settings.set(
                        PlaybackSettingKeys.STREAM_ON_METERED,
                        StreamOnMetered.NEVER,
                    )
                }
                bridge.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
                assertFalse(h.run { bridge.beforePlay(StartOrigin.EXTERNAL) })
                assertEquals(
                    PlaybackIssue.METERED_BLOCKED,
                    bridge.nowPlaying.value?.issue,
                )
            } finally {
                bridge.detach()
                player.release()
            }
        }
    }

    @Test
    fun aCommittedStartClearsTheBoundaryPauseOfThePreviousTimeline() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            h.seedCurrent()
            h.local.files[1] = "file:///synthetic-one.mp3"
            h.lookup.items.value += 2L to media(id = 2)
            h.lookup.items.value += 3L to media(id = 3)
            h.local.files[3] = "file:///synthetic-three.mp3"
            h.publish(h.store.queue.copy(upNext = listOf(QueueItem(2, QueueOrigin.UP_NEXT))))
            h.network.setStatus(
                FakeNetworkMonitor.ONLINE
                    .copy(isMetered = true),
            )
            h.run {
                h.settings.set(
                    PlaybackSettingKeys.STREAM_ON_METERED,
                    StreamOnMetered.ASK,
                )
            }
            val player = h.playerFactory.create()
            val bridge =
                AndroidServiceBridge(player, h.run { h.access.open() }, h.access, h.scope, { true }, {}, player::pause)
            bridge.attach()
            try {
                pumpUntil { player.mediaItemCount == 2 }
                bridge.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
                // Episode 3 is downloaded; the stale pause must not gate it on the old next item.
                assertEquals(PlayResult.Started, h.run { bridge.start(StartRequest.Episode(3), StartOrigin.UI) })
                pumpUntil { player.episodeIdOrNull() == 3L }
                assertTrue(h.run { bridge.beforePlay(StartOrigin.UI) })
            } finally {
                bridge.detach()
                player.release()
            }
        }
    }

    @Test
    fun stopStillStopsPlaybackAndTheServiceWhenThePositionSaveFails() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            h.seedCurrent()
            val player = h.playerFactory.create()
            var stopped = false
            val bridge =
                AndroidServiceBridge(
                    player,
                    h.run { h.access.open() },
                    h.access,
                    h.scope,
                    { true },
                    { stopped = true },
                    player::pause,
                )
            bridge.attach()
            try {
                pumpUntil { player.mediaItemCount == 1 }
                h.positions.failure = IllegalStateException("position write down")
                assertFailsWith<IllegalStateException> { h.run { bridge.stop() } }
                assertEquals(Player.STATE_IDLE, player.playbackState)
                assertTrue(stopped)
            } finally {
                h.positions.failure = null
                bridge.detach()
                player.release()
            }
        }
    }

    @Test
    fun externalResumeSelectsFirstPlayableAndChecksMeteredPolicy() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            h.lookup.items.value =
                mapOf(
                    1L to media(type = SourceType.YOUTUBE_CHANNEL),
                    2L to media(id = 2),
                )
            h.publish(
                VirtualQueue(
                    1,
                    null,
                    listOf(QueueItem(1, QueueOrigin.UP_NEXT), QueueItem(2, QueueOrigin.UP_NEXT)),
                    emptyList(),
                ),
            )
            h.network.setStatus(
                FakeNetworkMonitor.ONLINE
                    .copy(isMetered = true),
            )
            h.run {
                h.settings.set(
                    PlaybackSettingKeys.STREAM_ON_METERED,
                    StreamOnMetered.NEVER,
                )
            }
            val player = h.playerFactory.create()
            val bridge =
                AndroidServiceBridge(player, h.run { h.access.open() }, h.access, h.scope, { true }, {}, player::pause)
            bridge.attach()
            try {
                assertFailsWith<UnsupportedOperationException> { h.run { bridge.resume(StartOrigin.EXTERNAL) } }
                assertNull(h.store.queue.current)
                assertEquals(0, player.mediaItemCount)
                h.run {
                    h.settings.set(
                        PlaybackSettingKeys.STREAM_ON_METERED,
                        StreamOnMetered.ASK,
                    )
                }
                val resumed = h.run { bridge.resume(StartOrigin.EXTERNAL) }
                assertEquals("episode:2", resumed.mediaItems.first().mediaId)
                assertEquals(
                    listOf(1L),
                    h.store.queue.upNext
                        .map { it.episodeId },
                )
                assertNull(h.gate.pendingConsent.value)
                assertEquals(0, player.mediaItemCount)
            } finally {
                bridge.detach()
                player.release()
            }
        }
    }

    @Test fun falseResumptionNeverTouchesTheBridge() {
        var calls = 0
        val provider =
            ResumptionProvider {
                calls++
                error("No boot-card loading in M4")
            }
        assertFailsWith<UnsupportedOperationException> { runBlocking { provider.resume(false, StartOrigin.EXTERNAL) } }
        assertEquals(0, calls)
    }
}
