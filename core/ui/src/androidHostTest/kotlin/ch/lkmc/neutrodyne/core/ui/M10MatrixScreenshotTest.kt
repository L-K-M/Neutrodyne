// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w360dp-h900dp-mdpi")
class M10MatrixScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun everyEpisodeStateAndBrand() =
        capture(
            matrixAppearances(if (full) MatrixTarget.ANDROID_FULL else MatrixTarget.ANDROID_PR) + matrixHighContrast,
        )

    @Test
    @Config(qualifiers = "en-rXA-w360dp-h900dp-mdpi")
    fun expandedPseudoLocale() = capture(listOf(MatrixAppearance("light", 1.5f)), "-en-xa")

    @Test
    @Config(qualifiers = "ar-rXB-w360dp-h900dp-mdpi")
    fun rtlPseudoLocale() =
        capture(
            if (full) {
                matrixAppearances(MatrixTarget.ANDROID_FULL).map { it.copy(direction = LayoutDirection.Rtl) }
            } else {
                listOf(MatrixAppearance("dark", 2f, LayoutDirection.Rtl))
            },
            "-ar-xb",
        )

    private val full: Boolean get() = System.getProperty("neutrodyne.screenshotTier", "pr") == "full"

    private fun capture(
        appearances: List<MatrixAppearance>,
        suffix: String = "",
    ) {
        installFakeImageLoader()
        var appearance by mutableStateOf(appearances.first())
        var state by mutableStateOf<List<MatrixRow>?>(matrixRowPages.first())
        var subject by mutableStateOf(MatrixSupplement.BRAND)
        rule.setContent {
            MatrixSurface(appearance) {
                state?.let { MatrixEpisodePage(it) }
                    ?: MatrixSupplementContent(subject)
            }
        }
        for (variant in appearances) {
            for ((index, page) in matrixRowPages.withIndex()) {
                rule.runOnIdle {
                    appearance = variant
                    state = page
                }
                rule.waitForIdle()
                rule.onAllNodesWithText(page.first().row.title).fetchSemanticsNodes().forEach { node ->
                    check(node.boundsInRoot.top >= 0 && node.boundsInRoot.bottom <= 900) {
                        "${variant.name}$suffix clips a row on page $index: ${node.boundsInRoot}"
                    }
                }
                val number = index.toString().padStart(2, '0')
                rule.onRoot().captureRoboImage("screenshots/android/m10-rows-p$number-${variant.name}$suffix.png")
            }
            for (supplement in MatrixSupplement.entries) {
                rule.runOnIdle {
                    appearance = variant
                    state = null
                    subject = supplement
                }
                rule.waitForIdle()
                rule.onRoot().captureRoboImage(
                    "screenshots/android/m10-${supplement.name.lowercase()}-${variant.name}$suffix.png",
                )
            }
        }
    }
}
