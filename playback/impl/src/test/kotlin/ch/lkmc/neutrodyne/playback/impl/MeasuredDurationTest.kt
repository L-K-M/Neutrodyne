// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.paging.PagingSource
import ch.lkmc.neutrodyne.core.database.EpisodeRowProjection
import ch.lkmc.neutrodyne.core.database.FeedQueryBuilder
import ch.lkmc.neutrodyne.core.domain.PlaybackMediaLookup
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.testing.FakePlaybackMediaLookup
import ch.lkmc.neutrodyne.core.testing.FakePositionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MeasuredDurationTest {
    @Test fun waitsForReadyAndLaterKnownTimelineThenWritesOnlyOnce() =
        runTest {
            val exo = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
            var duration = C.TIME_UNSET
            var phase = Player.STATE_BUFFERING
            val player =
                object : ForwardingPlayer(exo) {
                    override fun getDuration() = duration

                    override fun getPlaybackState() = phase
                }
            val lookup = FakePlaybackMediaLookup().apply { items.value = mapOf(1L to media()) }
            val positions = FakePositionStore()
            val measured = MeasuredDuration(player, lookup, positions, backgroundScope, Dispatchers.Unconfined)
            try {
                player.setMediaItem(
                    MediaItem
                        .Builder()
                        .setMediaId("episode:1")
                        .setUri("https://example.org/1")
                        .build(),
                )
                measured.attach()
                runCurrent()
                assertTrue(positions.calls.isEmpty())
                phase = Player.STATE_READY
                measured.onPlaybackStateChanged(phase)
                runCurrent()
                assertTrue(positions.calls.isEmpty())
                duration = 123_456
                measured.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
                runCurrent()
                assertEquals(123_456, positions.measuredDurations[1])
                duration = 126_000
                repeat(3) { measured.onPlaybackStateChanged(phase) }
                runCurrent()
                assertEquals(1, positions.calls.size)
            } finally {
                measured.detach()
                exo.release()
            }
        }

    @Test fun toleranceIsStrictlyMoreThanOneSecond() =
        runTest {
            for (difference in listOf(0L, 1_000L, 1_001L)) {
                val exo = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
                val player =
                    object : ForwardingPlayer(exo) {
                        override fun getDuration() = 60_000L + difference

                        override fun getPlaybackState() = Player.STATE_READY
                    }
                val lookup =
                    FakePlaybackMediaLookup().apply {
                        items.value =
                            mapOf(1L to media().copy(measuredDurationMs = 60_000))
                    }
                val positions = FakePositionStore()
                val measured = MeasuredDuration(player, lookup, positions, backgroundScope, Dispatchers.Unconfined)
                try {
                    player.setMediaItem(
                        MediaItem
                            .Builder()
                            .setMediaId("episode:1")
                            .setUri("https://example.org/1")
                            .build(),
                    )
                    measured.attach()
                    runCurrent()
                    assertEquals(if (difference > 1_000) 1 else 0, positions.calls.size)
                } finally {
                    measured.detach()
                    exo.release()
                }
            }
        }

    @Test fun currentChangeAndDestructionCancelSuspendedMetadataRead() =
        runTest {
            val exo = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
            val player =
                object : ForwardingPlayer(exo) {
                    override fun getDuration() = 60_000L

                    override fun getPlaybackState() = Player.STATE_READY
                }
            var gate = CompletableDeferred<Unit>()
            val rows = FakePlaybackMediaLookup().apply { items.value = mapOf(1L to media(), 2L to media(2)) }
            val lookup =
                object : PlaybackMediaLookup by rows {
                    override suspend fun get(episodeId: Long) = rows.get(episodeId).also { gate.await() }
                }
            val positions = FakePositionStore()
            val measured = MeasuredDuration(player, lookup, positions, backgroundScope, Dispatchers.Unconfined)
            try {
                player.setMediaItem(
                    MediaItem
                        .Builder()
                        .setMediaId("episode:1")
                        .setUri("https://example.org/1")
                        .build(),
                )
                measured.attach()
                runCurrent()
                player.setMediaItem(
                    MediaItem
                        .Builder()
                        .setMediaId("episode:2")
                        .setUri("https://example.org/2")
                        .build(),
                )
                measured.onMediaItemTransition(
                    player.currentMediaItem,
                    Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
                )
                runCurrent()
                gate.complete(Unit)
                runCurrent()
                assertEquals(mapOf(2L to 60_000L), positions.measuredDurations)
                measured.detach()
            } finally {
                measured.detach()
                exo.release()
            }
        }

    @Test fun realRoomMetadataUpdateKeepsCurrentSourceProgressAndFeedDuration() =
        runTest {
            val f = AdapterFixture(this)
            val measurementPlayer =
                object : ForwardingPlayer(f.player) {
                    override fun getPlaybackState() = Player.STATE_READY

                    override fun getDuration() = AdapterFixture.DURATION_MS
                }
            val measured =
                MeasuredDuration(
                    measurementPlayer,
                    f.lookup,
                    f.positions,
                    f.playerScope,
                    Dispatchers.Unconfined,
                    f.failures::add,
                )
            try {
                val id = f.seed(1).single()
                // A deliberately incorrect feed hint must survive player enrichment.
                f.sql("UPDATE episode SET durationMs = 42000 WHERE id = $id")
                f.positions.save(id, 80_000, null, PositionSource.STREAM, f.clock.now(), 0) { true }
                f.attach()
                f.start(listOf(id), 80_000)
                awaitAdapter { f.player.episodeIdOrNull() == id }
                val beforeSource = f.player.currentMediaItem!!.localConfiguration
                f.sources.created.forEach { it.setCanUpdateMediaItems(true) }
                val created = f.sources.created.size
                f.player.calls.clear()
                measured.attach()
                awaitAdapter { f.player.calls.contains("metadata") }
                val state = f.db.episodeStateDao().byEpisode(id)
                val episode = f.db.episodeDao().byId(id)
                val position = f.db.positionDao().byEpisode(id)
                assertEquals(AdapterFixture.DURATION_MS, state?.measuredDurationMs)
                assertEquals(42_000, episode?.durationMs)
                assertEquals(80_000, position?.positionMs)
                assertEquals(80_000, f.player.currentPosition)
                assertEquals(beforeSource, f.player.currentMediaItem!!.localConfiguration)
                assertEquals(created, f.sources.created.size)
                assertTrue(f.player.calls.none { it == "set" || it == "prepare" })
                assertEquals(AdapterFixture.DURATION_MS, f.lookup.get(id)?.measuredDurationMs)
                val query = FeedQueryBuilder.page(FeedSource.All, FeedFilters(), FeedOrder.NEWEST_FIRST)
                val page =
                    f.db
                        .feedDao()
                        .page(query)
                        .load(PagingSource.LoadParams.Refresh(null, 10, false))
                val rows = assertIs<PagingSource.LoadResult.Page<Int, EpisodeRowProjection>>(page)
                assertEquals(AdapterFixture.DURATION_MS, rows.data.single().durationMs)
                assertTrue(f.failures.isEmpty(), f.failures.toString())
            } finally {
                measured.detach()
                f.close()
            }
        }
}
