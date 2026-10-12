// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade

/** Memory-key tiers (08 Request tiers and memory keys). */
public enum class CoverTier { THUMB, HERO }

/** The aspect a cover request is decoded for. */
public enum class CoverAspect { SQUARE, WIDE_16_9 }

/**
 * 08 "Request tiers and memory keys": builds the [ImageRequest] for an [ArtworkRef] with the
 * explicit `art:{key}:v{version}:{tier}` memory key, so THUMB and HERO bitmaps coexist and a hero
 * lands on its thumb instantly. Crossfade is set per request (not on the loader) so a change of
 * the system animator scale applies without rebuilding the singleton.
 */
public object Covers {
    /** Rows, tiles and the mini player: 128 dp in pixels, capped at 384 px (08). */
    public fun thumbPx(density: Density): Int = with(density) { THUMB_DP.roundToPx() }.coerceAtMost(THUMB_MAX_PX)

    /**
     * The request for [ref]. HERO decodes at 1024 px and uses the THUMB bitmap as its
     * placeholder; WIDE_16_9 requests 320 × 180 px so 04's thumbnail chain stays on `mqdefault`.
     *
     * Deviation (08 Request tiers and memory keys, 2026-10-07): Coil 3.6.3's public
     * `crossfade` takes a Boolean (its fixed duration is 200 ms), so the documented 150 ms
     * becomes Coil's default while the on/off semantics are unchanged.
     */
    public fun request(
        context: PlatformContext,
        ref: ArtworkRef,
        tier: CoverTier,
        density: Density,
        aspect: CoverAspect = CoverAspect.SQUARE,
        crossfade: Boolean = true,
    ): ImageRequest =
        ImageRequest
            .Builder(context)
            .data(ref)
            .apply {
                when {
                    tier == CoverTier.HERO -> size(HERO_PX)
                    aspect == CoverAspect.WIDE_16_9 -> size(WIDE_PX, WIDE_PX_HEIGHT)
                    else -> size(thumbPx(density))
                }
            }.memoryCacheKey("art:${ref.key}:v${ref.version}:${tierKey(tier)}")
            .apply {
                if (tier == CoverTier.HERO) {
                    placeholderMemoryCacheKey("art:${ref.key}:v${ref.version}:t")
                }
            }.crossfade(crossfade)
            .build()

    private fun tierKey(tier: CoverTier): String =
        when (tier) {
            CoverTier.THUMB -> "t"
            CoverTier.HERO -> "h"
        }

    private val THUMB_DP = 128.dp
    private const val THUMB_MAX_PX = 384
    private const val HERO_PX = 1024
    private const val WIDE_PX = 320
    private const val WIDE_PX_HEIGHT = 180
}
