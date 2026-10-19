// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.ui.root

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.Posture
import ch.lkmc.neutrodyne.core.model.HingeOrientation
import ch.lkmc.neutrodyne.core.model.HingeState
import ch.lkmc.neutrodyne.core.model.WindowHinge
import ch.lkmc.neutrodyne.core.model.WindowPosture
import ch.lkmc.neutrodyne.core.model.WindowRectDp
import kotlinx.collections.immutable.toImmutableList

/** Adaptive's Android hinge bounds are window pixels, never content-relative coordinates. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
internal fun windowPostureDp(
    posture: Posture,
    density: Float,
): WindowPosture {
    require(density.isFinite() && density > 0f)
    return WindowPosture(
        posture.hingeList
            .map { hinge ->
                val b = hinge.bounds
                WindowHinge(
                    WindowRectDp(b.left / density, b.top / density, b.right / density, b.bottom / density),
                    if (hinge.isVertical) HingeOrientation.VERTICAL else HingeOrientation.HORIZONTAL,
                    if (hinge.isFlat) HingeState.FLAT else HingeState.HALF_OPENED,
                    hinge.isSeparating,
                )
            }.toImmutableList(),
    )
}
