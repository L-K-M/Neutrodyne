// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import ch.lkmc.neutrodyne.core.model.HingeOrientation
import ch.lkmc.neutrodyne.core.model.HingeState
import ch.lkmc.neutrodyne.core.model.WindowPosture
import ch.lkmc.neutrodyne.core.model.WindowRectDp

internal enum class PlayerContentLayout { PORTRAIT, LANDSCAPE, TABLETOP }

/** Hinge bounds are window-relative dp; callers subtract their measured window offset. */
internal fun tabletopHinge(posture: WindowPosture): WindowRectDp? =
    posture.hinges
        .firstOrNull {
            it.orientation == HingeOrientation.HORIZONTAL && it.state == HingeState.HALF_OPENED
        }?.bounds

internal fun playerContentLayout(
    width: Float,
    height: Float,
    posture: WindowPosture,
): PlayerContentLayout =
    when {
        tabletopHinge(posture) != null -> PlayerContentLayout.TABLETOP
        height < 480f || width > height -> PlayerContentLayout.LANDSCAPE
        else -> PlayerContentLayout.PORTRAIT
    }
