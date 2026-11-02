// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
enum class EpisodeProgress { UNPLAYED, IN_PROGRESS, PLAYED }

@Serializable
data class EffectiveEpisodeState(
    val state: EpisodeProgress,
    val positionMs: Long,
    val playedAt: Long?,
    val startedAt: Long?,
    val favourite: Boolean,
) {
    val allowsUpNext: Boolean get() = state != EpisodeProgress.PLAYED
}

/** Projection only: keep raw played/pos clocks even when this projection hides their values. */
object EpisodeStateRules {
    fun derive(fields: Map<String, FieldState>): EffectiveEpisodeState {
        val mark = fields["played"]
        val played = (mark?.v as? JsonPrimitive)?.booleanOrNull == true
        val pos = fields["pos"]
        val listening = pos != null && !pos.reset && positionMs(pos) > 0L
        val favourite = (fields["fav"]?.v as? JsonPrimitive)?.booleanOrNull == true
        if (played && (!listening || requireNotNull(pos.c) <= requireNotNull(mark.c))) {
            val playedAt = (fields["playedAt"]?.v as? JsonPrimitive)?.takeUnless { it == JsonNull }?.longOrNull
            return EffectiveEpisodeState(EpisodeProgress.PLAYED, 0L, playedAt, null, favourite)
        }
        if (listening) {
            return EffectiveEpisodeState(
                EpisodeProgress.IN_PROGRESS,
                positionMs(requireNotNull(pos)),
                null,
                requireNotNull(pos.c).millis,
                favourite,
            )
        }
        return EffectiveEpisodeState(EpisodeProgress.UNPLAYED, 0L, null, null, favourite)
    }
}
