// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

/** Geometry in dp, measured from the full window rather than the navigation suite's changing size. */
internal data class PlayerGeometry(
    val width: Float,
    val height: Float,
    val statusBar: Float = 0f,
    val artworkAspect: Float = 1f,
    val startInset: Float = 0f,
)

internal data class PlayerMorph(
    val inset: Float,
    val endInset: Float,
    val corner: Float,
    val artworkSize: Float,
    val artworkHeight: Float,
    val artworkX: Float,
    val artworkY: Float,
    val artworkCorner: Float,
    val miniAlpha: Float,
    val controlsAlpha: Float,
    val controlsOffset: Float,
    val backgroundMix: Float,
) {
    public companion object {
        public fun at(
            progress: Float,
            geometry: PlayerGeometry,
        ): PlayerMorph {
            val p = progress.coerceIn(0f, 1f)
            val fullArt =
                minOf(
                    (geometry.width - ART_MARGIN).coerceAtLeast(MINI_ART),
                    geometry.height * ART_HEIGHT_FRACTION,
                    MAX_ART,
                )
            return PlayerMorph(
                inset = mix(geometry.startInset + SHEET_INSET, 0f, p),
                endInset = mix(SHEET_INSET, 0f, p),
                corner = mix(16f, 0f, p),
                artworkSize = mix(MINI_ART, fullArt, p),
                artworkHeight = mix(MINI_ART, fullArt / geometry.artworkAspect, p),
                artworkX = mix(8f, (geometry.width - fullArt) / 2, p),
                artworkY = mix(8f, geometry.statusBar + TOP_BAR, p),
                artworkCorner = mix(10f, 24f, p),
                miniAlpha = (1f - p / MINI_FADE_END).coerceIn(0f, 1f),
                controlsAlpha = ((p - FULL_FADE_START) / (1f - FULL_FADE_START)).coerceIn(0f, 1f),
                controlsOffset = mix(24f, 0f, p),
                backgroundMix = (p / BACKGROUND_FADE_END).coerceIn(0f, 1f),
            )
        }

        private fun mix(
            start: Float,
            end: Float,
            p: Float,
        ): Float = start + (end - start) * p

        private const val SHEET_INSET = 8f
        private const val MINI_ART = 48f
        private const val ART_MARGIN = 48f
        private const val ART_HEIGHT_FRACTION = 0.45f
        private const val MAX_ART = 480f
        private const val TOP_BAR = 56f
        private const val MINI_FADE_END = 0.2f
        private const val FULL_FADE_START = 0.6f
        private const val BACKGROUND_FADE_END = 0.3f
    }
}
