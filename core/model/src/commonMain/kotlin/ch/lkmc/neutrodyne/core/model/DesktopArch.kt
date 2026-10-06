// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/** Desktop CPU architectures the app ships for (11 Platform matrix); `wire` is the manifest value. */
enum class DesktopArch(
    val wire: String,
) {
    X64("x64"),
    ARM64("arm64"),
}
