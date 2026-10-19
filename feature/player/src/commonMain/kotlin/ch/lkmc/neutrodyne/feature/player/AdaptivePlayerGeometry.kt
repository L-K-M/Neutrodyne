// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.player

import ch.lkmc.neutrodyne.core.model.HingeOrientation
import ch.lkmc.neutrodyne.core.model.HingeState
import ch.lkmc.neutrodyne.core.model.WindowPosture
import ch.lkmc.neutrodyne.core.model.WindowRectDp

internal enum class PlayerLayoutKind { PORTRAIT, LANDSCAPE, TABLETOP }

internal data class AdaptivePlayerGeometry(
    val kind: PlayerLayoutKind,
    val art: WindowRectDp,
    val controls: WindowRectDp,
)

/** Input and output are dp. Origin is measured after the host's cutout/IME/safe-drawing insets. */
internal fun adaptivePlayerGeometry(
    width: Float,
    height: Float,
    originX: Float,
    originY: Float,
    posture: WindowPosture,
): AdaptivePlayerGeometry {
    require(listOf(width, height, originX, originY).all { it.isFinite() })
    require(width >= 0f && height >= 0f)
    val hinge =
        posture.hinges
            .firstOrNull {
                it.orientation == HingeOrientation.HORIZONTAL && it.state == HingeState.HALF_OPENED &&
                    it.bounds.left <= originX && it.bounds.right >= originX + width &&
                    it.bounds.top > originY && it.bounds.bottom < originY + height
            }?.bounds
    if (hinge != null) {
        return AdaptivePlayerGeometry(
            PlayerLayoutKind.TABLETOP,
            WindowRectDp(0f, 0f, width, hinge.top - originY),
            WindowRectDp(0f, hinge.bottom - originY, width, height),
        )
    }
    if (width > height) {
        val split = width * ART_COLUMN_FRACTION
        return AdaptivePlayerGeometry(
            PlayerLayoutKind.LANDSCAPE,
            WindowRectDp(0f, 0f, split, height),
            WindowRectDp(split, 0f, width, height),
        )
    }
    val split = (width * PORTRAIT_ART_FRACTION).coerceAtMost(height * ART_COLUMN_FRACTION)
    return AdaptivePlayerGeometry(
        PlayerLayoutKind.PORTRAIT,
        WindowRectDp(0f, 0f, width, split),
        WindowRectDp(0f, split, width, height),
    )
}

private const val ART_COLUMN_FRACTION = 0.4f
private const val PORTRAIT_ART_FRACTION = 0.65f
