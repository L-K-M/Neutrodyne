// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/**
 * What a row swipe commits (08 EpisodeRow; also the type of the `appearance.swipe_*` keys).
 * [NONE] disables the direction.
 */
enum class SwipeAction {
    ADD_UP_NEXT,
    MARK_PLAYED,
    DOWNLOAD,
    REMOVE_FROM_UP_NEXT,
    DELETE_DOWNLOAD,
    NONE,
}
