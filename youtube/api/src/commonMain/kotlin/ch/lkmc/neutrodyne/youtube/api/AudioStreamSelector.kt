// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.api

enum class AudioDelivery { HTTP, OTHER }

enum class AudioTrackType { ORIGINAL, DEFAULT, DUBBED, DESCRIPTIVE, SECONDARY }

/** Selection metadata only: signed URLs stay inside the engine's memory-only mapper. */
data class AudioCandidate(
    val itag: Int,
    val mimeType: String,
    val codecs: String?,
    val averageBitrate: Int?,
    val delivery: AudioDelivery,
    val hasUrl: Boolean,
    val trackType: AudioTrackType?,
    val trackLanguage: String?,
    val audioTrackId: String?,
    val isDrc: Boolean,
)

/** Track safety precedes DRC and itag pinning; a pin never forces a dubbed representation. */
object AudioStreamSelector {
    fun select(
        candidates: List<AudioCandidate>,
        pref: AudioPref,
    ): AudioCandidate? {
        var choices =
            candidates.filter {
                it.hasUrl && it.delivery == AudioDelivery.HTTP &&
                    it.mimeType.startsWith("audio/")
            }
        if (choices.isEmpty()) return null

        choices = choices.prefer { it.trackType == AudioTrackType.ORIGINAL }
        if (choices.none { it.trackType == AudioTrackType.ORIGINAL }) {
            choices =
                choices.prefer { it.trackType != AudioTrackType.DUBBED && it.trackType != AudioTrackType.DESCRIPTIVE }
            pref.preferredLanguage?.let { language ->
                choices =
                    choices.prefer { it.trackLanguage.equals(language, ignoreCase = true) }
            }
            choices = choices.prefer { it.trackType == null }
            choices = choices.prefer { it.trackType == AudioTrackType.SECONDARY }
        }
        choices = choices.prefer { it.isDrc == pref.preferDrc }
        pref.pinnedItag?.let { pin -> choices.firstOrNull { it.itag == pin }?.let { return it } }
        return choices.minWithOrNull(
            compareBy<AudioCandidate> {
                pref.quality.ranks
                    .indexOf(it.itag)
                    .takeIf { rank -> rank >= 0 }
                    ?: Int.MAX_VALUE
            }.thenByDescending { it.averageBitrate ?: 0 }
                .thenBy { it.itag },
        )
    }

    private fun List<AudioCandidate>.prefer(predicate: (AudioCandidate) -> Boolean): List<AudioCandidate> =
        filter(predicate).ifEmpty {
            this
        }
}
