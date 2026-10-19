// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.settings

/** Minimum grid cell width in dp; enum names are the portable stored representation. */
enum class LibraryDensity(
    val minCellWidthDp: Int,
) {
    SMALL(72),
    MEDIUM(100),
    LARGE(152),
}
