// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

/** FULL includes PR. Runtime property/assumption adapters and the registry belong to W9. */
enum class ScreenshotTier { PR, FULL }

/**
 * Stable capture identity, shared by Android/desktop inventories and coverage maps.
 * Format: subject__state__theme__font__direction-locale__viewport__artwork.
 * W9 enumerates applicable cases before rendering; no automatic Cartesian product is implied.
 * Example: episode-row__unplayed__dark__f200__rtl-ar-xb__compact__colourful
 */
data class ScreenshotCaseId(
    val value: String,
) {
    init {
        require(FORMAT.matches(value)) { "Screenshot ID needs seven lowercase kebab-case segments" }
    }

    private companion object {
        val FORMAT = Regex("[a-z0-9]+(?:-[a-z0-9]+)*(?:__[a-z0-9]+(?:-[a-z0-9]+)*){6}")
    }
}

/** Separate golden roots make the same case ID usable on both renderers. */
enum class ScreenshotPlatform { ANDROID, DESKTOP }

data class ScreenshotCase(
    val id: ScreenshotCaseId,
    val platform: ScreenshotPlatform,
    val requiredTier: ScreenshotTier,
)
