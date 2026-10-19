// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** One player slot/collector. Landscape and tabletop are layouts within either presentation. */
enum class PlayerPresentation { SHEET, SIDE_PANEL }

/** Window-relative dp, after density conversion but before content/rail/panel offsets. */
data class WindowRectDp(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() }) { "Window bounds must be finite" }
        require(right >= left && bottom >= top) { "Window bounds must be ordered" }
    }
}

enum class HingeOrientation { HORIZONTAL, VERTICAL }

enum class HingeState { FLAT, HALF_OPENED }

data class WindowHinge(
    val bounds: WindowRectDp,
    val orientation: HingeOrientation,
    val state: HingeState,
    val isSeparating: Boolean,
)

/** Android shell supplies real hinges; desktop supplies the empty default. W6 owns conversion. */
data class WindowPosture(
    val hinges: ImmutableList<WindowHinge> = persistentListOf(),
)
