// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Label
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.Chapter
import ch.lkmc.neutrodyne.core.domain.ChapterRepository
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.CurrentChapter
import ch.lkmc.neutrodyne.core.model.EpisodeChapter
import ch.lkmc.neutrodyne.core.testing.FakeChapterRepository
import ch.lkmc.neutrodyne.playback.api.PlaybackSessionCommands
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ChaptersTest {
    @Test fun lateMetadataDuplicatesAndSourceWritesOncePerOccurrence() =
        runTest {
            val exo = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
            val player = TestPlayer(exo) { testScheduler.currentTime }
            val repo = FakeChapterRepository()
            var replacements = 0
            val repository =
                object : ChapterRepository by repo {
                    override suspend fun replace(
                        id: Long,
                        source: ChapterSource,
                        rows: List<EpisodeChapter>,
                    ) {
                        replacements++
                        repo.replace(id, source, rows)
                    }
                }
            val chapters = Chapters(player, repository, backgroundScope) {}
            try {
                chapters.attach()
                runCurrent()
                assertTrue(repo.writes.isEmpty())
                val tracks = tracks(chapter(0), chapter(1000))
                player.availableTracks = tracks
                chapters.onTracksChanged(tracks)
                runCurrent()
                chapters.onTracksChanged(tracks)
                runCurrent()
                assertEquals(1, repo.writes.size)
                assertEquals(1, replacements)
                assertEquals(
                    2,
                    repo.writes
                        .single()
                        .chapters.size,
                )
                assertEquals(listOf(1L), repo.loads)
                chapters.onMediaItemTransition(player.currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
                runCurrent()
                chapters.onTracksChanged(tracks)
                runCurrent()
                assertEquals(listOf(1L, 1L), repo.loads)
                assertEquals(1, repo.writes.size) // repository also suppresses identical writes
                assertEquals(2, replacements)
            } finally {
                chapters.detach()
                exo.release()
            }
        }

    @Test fun seekSpeedPauseAndExplicitEndBoundaries() =
        runTest {
            val exo = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
            val player = TestPlayer(exo) { testScheduler.currentTime }
            val repo = FakeChapterRepository()
            repo.replace(1, ChapterSource.PSC, listOf(row(0).copy(endMs = 500), row(1000), row(2000)))
            var current: CurrentChapter? = null
            val chapters = Chapters(player, repo, backgroundScope) { current = it }
            try {
                chapters.attach()
                runCurrent()
                assertEquals(0, current?.index)
                player.advancing = true
                chapters.onIsPlayingChanged(true)
                advanceTimeBy(500)
                runCurrent()
                assertNull(current) // gap at the explicit end
                advanceTimeBy(500)
                runCurrent()
                assertEquals(1, current?.index)
                player.changeSpeed(2f)
                chapters.onPlaybackParametersChanged(player.playbackParameters)
                advanceTimeBy(500)
                runCurrent()
                assertEquals(2, current?.index)
                player.seekTo(0)
                chapters.onPlaybackParametersChanged(player.playbackParameters)
                player.advancing = false
                chapters.onIsPlayingChanged(false)
                advanceTimeBy(5000)
                runCurrent()
                assertEquals(0, current?.index)
                chapters.detach()
                assertNull(current)
            } finally {
                chapters.detach()
                exo.release()
            }
        }

    @Test fun chapterCommandsUseCanonicalSeekAndNeverWrap() =
        runTest {
            val exo = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
            val player = TestPlayer(exo) { testScheduler.currentTime }
            val repo = FakeChapterRepository()
            repo.replace(1, ChapterSource.PSC, listOf(row(0), row(10_000)))
            val chapters = Chapters(player, repo, backgroundScope) {}
            try {
                chapters.attach()
                runCurrent()
                chapters.command(PlaybackSessionCommands.CHAPTER_NEXT)
                assertEquals(listOf(10_000L), player.seeks)
                chapters.command(PlaybackSessionCommands.CHAPTER_NEXT)
                assertEquals(1, player.seeks.size)
                player.seekTo(13_001)
                chapters.command(PlaybackSessionCommands.CHAPTER_PREV)
                assertEquals(10_000L, player.seeks.last())
                chapters.command(PlaybackSessionCommands.CHAPTER_PREV)
                assertEquals(0L, player.seeks.last())
                repo.rows.value = emptyMap()
                runCurrent()
                val before = player.seeks.size
                chapters.command(PlaybackSessionCommands.CHAPTER_NEXT)
                assertEquals(before, player.seeks.size)
                assertNull(chapters.command("nd.unknown"))
            } finally {
                chapters.detach()
                exo.release()
            }
        }

    @Test fun itemChangeCancelsQueuedWritesAndOldObservation() =
        runTest {
            val exo = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
            val player = TestPlayer(exo) { testScheduler.currentTime }
            val repo = FakeChapterRepository()
            val chapters = Chapters(player, repo, backgroundScope) {}
            try {
                chapters.attach()
                runCurrent()
                player.availableTracks = tracks(chapter(0))
                chapters.onTracksChanged(player.currentTracks)
                val stale = player.currentTracks
                player.id = 2
                player.availableTracks = Tracks.EMPTY
                chapters.onMediaItemTransition(player.currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                runCurrent()
                assertTrue(repo.writes.isEmpty())
                assertEquals(listOf(1L, 2L), repo.loads)
                assertEquals(listOf(1L, 2L), repo.currentLoads)
                chapters.onTracksChanged(stale)
                runCurrent()
                assertTrue(repo.writes.isEmpty())
            } finally {
                chapters.detach()
                exo.release()
            }
        }

    @Test fun pendingCurrentLoadDoesNotBlockMetadataOrCommandsAndDetaches() =
        runTest {
            val exo = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
            val player = TestPlayer(exo) { testScheduler.currentTime }
            val fake = FakeChapterRepository()
            val pending = CompletableDeferred<Unit>()
            val cancelled = mutableListOf<Long>()
            val repository =
                object : ChapterRepository by fake {
                    override suspend fun ensureCurrentLoaded(episodeId: Long) {
                        try {
                            pending.await()
                        } finally {
                            cancelled += episodeId
                        }
                    }
                }
            val chapters = Chapters(player, repository, backgroundScope) {}
            try {
                chapters.attach()
                runCurrent()
                player.availableTracks = tracks(chapter(0), chapter(1000))
                chapters.onTracksChanged(player.currentTracks)
                runCurrent()
                chapters.command(PlaybackSessionCommands.CHAPTER_NEXT)
                assertEquals(listOf(1000L), player.seeks)
                player.id = 2
                chapters.onMediaItemTransition(player.currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                runCurrent()
                assertEquals(listOf(1L), cancelled)
                chapters.detach()
                runCurrent()
                assertEquals(listOf(1L, 2L), cancelled)
            } finally {
                chapters.detach()
                exo.release()
            }
        }

    @Test fun priorityChangesReevaluateCurrentAndRetainEmbeddedFallback() =
        runTest {
            val exo = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
            val player = TestPlayer(exo) { testScheduler.currentTime }
            val repo = FakeChapterRepository()
            repo.replace(1, ChapterSource.PSC, listOf(row(0)))
            var current: CurrentChapter? = null
            val chapters = Chapters(player, repo, backgroundScope) { current = it }
            try {
                chapters.attach()
                runCurrent()
                assertEquals(ChapterSource.PSC, current?.chapter?.source)
                player.availableTracks = tracks(chapter(0))
                chapters.onTracksChanged(player.currentTracks)
                runCurrent()
                assertEquals(ChapterSource.PSC, current?.chapter?.source)
                assertTrue(
                    repo.rows.value[1]
                        .orEmpty()
                        .any { it.source == ChapterSource.MP4 },
                )
                val json = row(0).copy(source = ChapterSource.PODCASTING20_JSON)
                repo.replace(1, json.source, listOf(json.copy(hidden = true)))
                runCurrent()
                assertEquals(ChapterSource.PSC, current?.chapter?.source)
                repo.replace(1, json.source, listOf(json))
                runCurrent()
                assertEquals(ChapterSource.PODCASTING20_JSON, current?.chapter?.source)
                assertTrue(
                    repo.rows.value[1]
                        .orEmpty()
                        .any { it.source == ChapterSource.MP4 },
                )
            } finally {
                chapters.detach()
                exo.release()
            }
        }

    @Test fun mapsHiddenUnsetTimesAndLabelValue() {
        val rows = Chapters.fromTracks(tracks(chapter(C.TIME_UNSET), chapter(0), chapter(1000, hidden = true)))
        assertEquals(listOf(0L, 1000L), rows[ChapterSource.MP4]?.map { it.startMs })
        assertEquals("chapter 0", rows[ChapterSource.MP4]?.first()?.title)
        assertTrue(rows[ChapterSource.MP4]!!.last().hidden)
        assertNull(rows[ChapterSource.MP4]!!.last().endMs)
    }

    private class TestPlayer(
        exo: Player,
        private val now: () -> Long,
    ) : ForwardingPlayer(exo) {
        var id = 1L
        private var base = 0L
        private var sampledAt = now()
        private var speed = 1f
        var advancing = false
            set(value) {
                base = currentPosition
                sampledAt = now()
                field = value
            }
        val seeks = mutableListOf<Long>()

        override fun getCurrentMediaItem(): MediaItem = MediaItem.Builder().setMediaId("episode:$id").build()

        override fun getCurrentPosition(): Long = base + if (advancing) ((now() - sampledAt) * speed).toLong() else 0

        var availableTracks = Tracks.EMPTY

        override fun getCurrentTracks(): Tracks = availableTracks

        override fun getDuration(): Long = C.TIME_UNSET

        override fun isPlaying(): Boolean = advancing

        override fun getPlaybackParameters() = PlaybackParameters(speed)

        override fun seekTo(positionMs: Long) {
            seeks += positionMs
            base = positionMs
            sampledAt = now()
        }

        fun changeSpeed(value: Float) {
            base = currentPosition
            sampledAt = now()
            speed = value
        }
    }

    private fun chapter(
        start: Long,
        hidden: Boolean = false,
    ): Chapter =
        Chapter
            .Builder()
            .setStartTimeMs(start)
            .setHidden(hidden)
            .setTitle(Label(null, "chapter $start"))
            .build()

    private fun tracks(vararg entries: Chapter): Tracks {
        val format =
            Format
                .Builder()
                .setSampleMimeType(
                    MimeTypes.AUDIO_AAC,
                ).setContainerMimeType(MimeTypes.AUDIO_MP4)
                .setMetadata(Metadata(*entries))
                .build()
        val group = Tracks.Group(TrackGroup(format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))
        return Tracks(ImmutableList.of(group, group))
    }

    private fun row(start: Long) = EpisodeChapter(start, null, "chapter", null, null, ChapterSource.PSC, false)
}
