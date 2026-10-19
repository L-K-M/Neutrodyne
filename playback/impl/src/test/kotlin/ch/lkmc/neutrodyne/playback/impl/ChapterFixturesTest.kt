// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.testing.FakeChapterRepository
import ch.lkmc.neutrodyne.playback.api.PlaybackSessionCommands
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChapterFixturesTest {
    @Test fun actualId3ChapterFramesExtractAndMap() = verify("chapters_id3.mp3", ChapterSource.ID3, Mp3Extractor())

    @Test fun actualNeroChaptersExtractAndMap() = verify("chapters_nero.m4b", ChapterSource.MP4, Mp4Extractor())

    @Test fun actualQuickTimeChaptersExtractAndMap() =
        verify(
            "chapters_quicktime.m4b",
            ChapterSource.MP4,
            Mp4Extractor(),
        )

    @Test fun quickTimeWinsWhenNeroAlsoPresent() = verify("chapters_both.m4b", ChapterSource.MP4, Mp4Extractor())

    @Test fun currentTrackMetadataAvailableWithVideoAndTextDisabled() {
        for (name in listOf("chapters_id3.mp3", "chapters_nero.m4b", "chapters_quicktime.m4b", "chapters_both.m4b")) {
            val bytes = checkNotNull(javaClass.getResourceAsStream("/media/$name")).use { it.readBytes() }
            val factory = ProgressiveMediaSource.Factory(DataSource.Factory { ByteArrayDataSource(bytes) })
            val player = TestExoPlayerBuilder(RuntimeEnvironment.getApplication()).build()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val chapters = Chapters(player, FakeChapterRepository(), scope) {}
            try {
                player.trackSelectionParameters =
                    player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                player.setMediaSource(
                    factory.createMediaSource(
                        androidx.media3.common.MediaItem
                            .Builder()
                            .setMediaId("episode:1")
                            .setUri("https://fixture.test/$name")
                            .build(),
                    ),
                )
                player.prepare()
                TestPlayerRunHelper.runUntilPlaybackState(player, androidx.media3.common.Player.STATE_READY)
                val rows = Chapters.fromTracks(player.currentTracks).values.flatten()
                assertEquals(listOf("Opening", "Closing"), rows.map { it.title }, name)
                chapters.attach()
                chapters.command(PlaybackSessionCommands.CHAPTER_NEXT)
                TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(player)
                assertEquals(1000L, player.currentPosition, name)
                chapters.command(PlaybackSessionCommands.CHAPTER_PREV)
                TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(player)
                assertEquals(0L, player.currentPosition, name)
            } finally {
                chapters.detach()
                scope.cancel()
                player.release()
            }
        }
    }

    private fun verify(
        name: String,
        source: ChapterSource,
        extractor: Extractor,
    ) {
        val formats = mutableListOf<Format>()
        extractor.init(
            object : ExtractorOutput {
                override fun track(
                    id: Int,
                    type: Int,
                ): TrackOutput =
                    object : TrackOutput by DiscardingTrackOutput() {
                        override fun format(format: Format) {
                            formats += format
                        }
                    }

                override fun endTracks() = Unit

                override fun seekMap(seekMap: SeekMap) = Unit
            },
        )
        val bytes = checkNotNull(javaClass.getResourceAsStream("/media/$name")).use { it.readBytes() }
        val reader = ByteArrayDataSource(bytes)
        var position = 0L
        try {
            var result = Extractor.RESULT_CONTINUE
            while (result != Extractor.RESULT_END_OF_INPUT) {
                reader.open(
                    DataSpec
                        .Builder()
                        .setUri("test://$name")
                        .setPosition(position)
                        .build(),
                )
                val input = DefaultExtractorInput(reader, position, bytes.size.toLong())
                val holder = PositionHolder()
                do {
                    result = extractor.read(input, holder)
                } while (result == Extractor.RESULT_CONTINUE)
                reader.close()
                position = holder.position
            }
            assertTrue(formats.isNotEmpty())
            val groups =
                formats.map { format ->
                    Tracks.Group(
                        TrackGroup(format),
                        false,
                        intArrayOf(C.FORMAT_HANDLED),
                        booleanArrayOf(true),
                    )
                }
            val rows = Chapters.fromTracks(Tracks(ImmutableList.copyOf(groups)))[source].orEmpty()
            assertEquals(listOf(0L, 1000L), rows.map { it.startMs }, name)
            assertEquals(listOf("Opening", "Closing"), rows.map { it.title }, name)
        } finally {
            reader.close()
            extractor.release()
        }
    }
}
