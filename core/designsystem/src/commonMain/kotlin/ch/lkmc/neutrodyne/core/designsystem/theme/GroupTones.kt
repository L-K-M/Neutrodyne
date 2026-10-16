// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import ch.lkmc.neutrodyne.core.model.art.ArtColors

/**
 * A group's three derived roles (08 ArtColors tones for monograms and groups): [dot] marks and
 * stripes the tab and mosaic, [container] fills empty mosaic cells, [onContainer] tints what sits
 * on it (the group icon or name grapheme). The 05 palette seeds are never rendered raw — every
 * role re-tones the seed to a fixed CIE L* with its chroma capped, so contrast holds for any hue.
 */
@Immutable
public data class GroupTones(
    val dot: Color,
    val container: Color,
    val onContainer: Color,
)

/**
 * The tones for a group seed ARGB. `null` (the user picked "no colour") falls back to the theme's
 * primary roles, per 08. Reads the same OS-dark flag as [MonogramPainter]; a forced app theme
 * resolves through `MaterialTheme`, so the fallback roles stay right there.
 */
@Composable
public fun groupTones(seedArgb: Int?): GroupTones {
    if (seedArgb == null) {
        val scheme = MaterialTheme.colorScheme
        return GroupTones(
            dot = scheme.primary,
            container = scheme.primaryContainer,
            onContainer = scheme.onPrimaryContainer,
        )
    }

    val dark = LocalSystemUiState.current.dark
    return if (dark) {
        GroupTones(
            dot = groupTone(seedArgb, DOT_DARK_L),
            container = groupTone(seedArgb, CONTAINER_DARK_L),
            onContainer = groupTone(seedArgb, ON_CONTAINER_DARK_L),
        )
    } else {
        GroupTones(
            dot = groupTone(seedArgb, DOT_LIGHT_L),
            container = groupTone(seedArgb, CONTAINER_LIGHT_L),
            onContainer = groupTone(seedArgb, ON_CONTAINER_LIGHT_L),
        )
    }
}

private fun groupTone(
    seedArgb: Int,
    l: Double,
): Color = Color(ArtColors.tone(seedArgb, l, maxChroma = GROUP_MAX_CHROMA))

private const val GROUP_MAX_CHROMA: Double = 48.0
private const val DOT_LIGHT_L: Double = 40.0
private const val DOT_DARK_L: Double = 80.0
private const val CONTAINER_LIGHT_L: Double = 90.0
private const val CONTAINER_DARK_L: Double = 30.0
private const val ON_CONTAINER_LIGHT_L: Double = 10.0
private const val ON_CONTAINER_DARK_L: Double = 90.0
