// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp3.Mp3Extractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackFixturesTest {
    @Test fun videoFixtureContainsRealH264AndAacTracks() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/media/h264_aac_8s.mp4")).use { it.readBytes() }
        val extractor =
            androidx.media3.extractor.mp4
                .Mp4Extractor()
        val types = mutableSetOf<String>()
        extractor.init(
            object : ExtractorOutput {
                override fun track(
                    id: Int,
                    type: Int,
                ): TrackOutput =
                    object : TrackOutput by DiscardingTrackOutput() {
                        override fun format(format: androidx.media3.common.Format) {
                            format.sampleMimeType?.let(types::add)
                        }
                    }

                override fun endTracks() = Unit

                override fun seekMap(seekMap: SeekMap) = Unit
            },
        )
        val source = ByteArrayDataSource(bytes)
        val holder = PositionHolder()
        try {
            source.open(DataSpec.Builder().setUri("test://video.mp4").build())
            var input = DefaultExtractorInput(source, 0, bytes.size.toLong())
            assertTrue(extractor.sniff(input))
            input.resetPeekPosition()
            while (true) {
                when (extractor.read(input, holder)) {
                    Extractor.RESULT_END_OF_INPUT -> {
                        break
                    }

                    Extractor.RESULT_SEEK -> {
                        source.close()
                        source.open(
                            DataSpec
                                .Builder()
                                .setUri("test://video.mp4")
                                .setPosition(holder.position)
                                .build(),
                        )
                        input = DefaultExtractorInput(source, holder.position, bytes.size.toLong())
                    }
                }
            }
            assertEquals(setOf("video/avc", "audio/mp4a-latm"), types)
        } finally {
            source.close()
            extractor.release()
        }
    }

    @Test fun syntheticCbrVbrAndSilenceFixturesHaveSeekableAudio() {
        for ((name, duration) in listOf("cbr_128k_2s.mp3" to 2, "vbr_xing_2s.mp3" to 2, "silence_gaps_3s.mp3" to 3)) {
            val bytes = checkNotNull(javaClass.getResourceAsStream("/media/$name")).use { it.readBytes() }
            val extractor =
                DefaultExtractorsFactory()
                    .setConstantBitrateSeekingEnabled(true)
                    .setDisableArtworkMetadata(true)
                    .createExtractors()
                    .filterIsInstance<Mp3Extractor>()
                    .single()
            var seekMap: SeekMap? = null
            extractor.init(
                object : ExtractorOutput {
                    override fun track(
                        id: Int,
                        type: Int,
                    ): TrackOutput {
                        assertEquals(C.TRACK_TYPE_AUDIO, type)
                        return DiscardingTrackOutput()
                    }

                    override fun endTracks() = Unit

                    override fun seekMap(value: SeekMap) {
                        seekMap = value
                    }
                },
            )
            val source = ByteArrayDataSource(bytes)
            try {
                source.open(DataSpec.Builder().setUri("test://$name").build())
                val input = DefaultExtractorInput(source, 0, bytes.size.toLong())
                assertTrue(name, extractor.sniff(input))
                input.resetPeekPosition()
                var result = Extractor.RESULT_CONTINUE
                while (result != Extractor.RESULT_END_OF_INPUT) {
                    result = extractor.read(input, PositionHolder())
                    assertTrue(name, result != Extractor.RESULT_SEEK)
                }
                val map = checkNotNull(seekMap)
                assertTrue(name, map.isSeekable)
                assertTrue(
                    "$name: ${map.durationUs}",
                    map.durationUs in duration * 1_000_000L - 100_000..duration * 1_000_000L + 100_000,
                )
            } finally {
                source.close()
                extractor.release()
            }
        }
    }
}
