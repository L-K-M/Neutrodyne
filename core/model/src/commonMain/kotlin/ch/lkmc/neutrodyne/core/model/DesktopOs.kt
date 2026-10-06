// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/** Desktop operating systems the app ships for (11 Platform matrix); `wire` is the manifest value. */
enum class DesktopOs(
    val wire: String,
) {
    WINDOWS("windows"),
    MACOS("macos"),
    LINUX("linux"),
}
