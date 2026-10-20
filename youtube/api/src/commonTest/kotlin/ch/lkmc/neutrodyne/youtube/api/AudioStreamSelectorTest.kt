// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioStreamSelectorTest {
    private fun audio(
        itag: Int = 140,
        type: AudioTrackType? = null,
        language: String? = null,
        drc: Boolean = false,
        bitrate: Int = 128000,
    ) = AudioCandidate(itag, "audio/mp4", "mp4a.40.2", bitrate, AudioDelivery.HTTP, true, type, language, language, drc)

    @Test fun originalWinsOverPreferredLanguageQualityDrcAndPin() {
        val original = audio(139, AudioTrackType.ORIGINAL, "de")
        assertEquals(
            original,
            AudioStreamSelector.select(
                listOf(audio(251, AudioTrackType.DUBBED, "en", true), original),
                AudioPref(AudioQuality.OPUS, true, 251, "en"),
            ),
        )
    }

    @Test fun dubbedAndDescriptiveCannotDisplaceOtherTracks() {
        for (excluded in listOf(AudioTrackType.DUBBED, AudioTrackType.DESCRIPTIVE)) {
            val safe = audio(139, AudioTrackType.SECONDARY, "fr")
            assertEquals(
                safe,
                AudioStreamSelector.select(
                    listOf(audio(140, excluded, "en"), safe),
                    AudioPref(preferredLanguage = "en"),
                ),
            )
        }
    }

    @Test fun languageThenUntypedThenSecondaryFallback() {
        val french = audio(251, AudioTrackType.DEFAULT, "fr")
        val untyped = audio(250)
        val secondary = audio(139, AudioTrackType.SECONDARY, "es")
        val choices = listOf(french, untyped, secondary)
        assertEquals(french, AudioStreamSelector.select(choices, AudioPref(preferredLanguage = "FR")))
        assertEquals(untyped, AudioStreamSelector.select(choices, AudioPref(preferredLanguage = "de")))
        assertEquals(secondary, AudioStreamSelector.select(listOf(french, secondary), AudioPref()))
    }

    @Test fun onlyDubbedOrOnlyDrcStillPlayable() {
        val dubbed = audio(type = AudioTrackType.DUBBED)
        assertEquals(dubbed, AudioStreamSelector.select(listOf(dubbed), AudioPref()))
        val drc = audio(drc = true)
        assertEquals(drc, AudioStreamSelector.select(listOf(drc), AudioPref()))
    }

    @Test fun drcPreferenceDoesNotMixSameItagRepresentations() {
        val plain = audio()
        val drc = audio(drc = true)
        assertEquals(plain, AudioStreamSelector.select(listOf(drc, plain), AudioPref()))
        assertEquals(drc, AudioStreamSelector.select(listOf(plain, drc), AudioPref(preferDrc = true)))
    }

    @Test fun qualityRanksAndPinFallback() {
        val choices = listOf(audio(249), audio(139), audio(250), audio(251), audio(140))
        for ((quality, expected) in listOf(
            AudioQuality.STANDARD to 140,
            AudioQuality.DATA_SAVER to 250,
            AudioQuality.OPUS to 251,
        )) {
            assertEquals(expected, AudioStreamSelector.select(choices, AudioPref(quality))?.itag)
            assertEquals(139, AudioStreamSelector.select(choices, AudioPref(quality, pinnedItag = 139))?.itag)
            assertEquals(expected, AudioStreamSelector.select(choices, AudioPref(quality, pinnedItag = 999))?.itag)
        }
    }

    @Test fun unknownItagsUseBitrateThenNumber() {
        assertEquals(
            998,
            AudioStreamSelector
                .select(
                    listOf(audio(999, bitrate = 200), audio(998, bitrate = 200), audio(997, bitrate = 100)),
                    AudioPref(),
                )?.itag,
        )
        assertEquals(
            140,
            AudioStreamSelector
                .select(
                    listOf(audio(999, bitrate = 900000), audio(140, bitrate = 100)),
                    AudioPref(),
                )?.itag,
        )
    }

    @Test fun noProgressiveAudioMeansNoSelection() {
        val base = audio()
        assertNull(AudioStreamSelector.select(emptyList(), AudioPref()))
        assertNull(
            AudioStreamSelector.select(
                listOf(
                    base.copy(delivery = AudioDelivery.OTHER),
                    base.copy(hasUrl = false),
                    base.copy(mimeType = "video/mp4"),
                ),
                AudioPref(),
            ),
        )
    }
}
