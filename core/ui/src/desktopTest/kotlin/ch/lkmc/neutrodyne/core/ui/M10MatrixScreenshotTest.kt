// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import io.github.takahirom.roborazzi.captureRoboImage
import java.util.Locale
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class M10MatrixScreenshotTest {
    @Test fun everyEpisodeStateAndBrand() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            installFakeImageLoader()
            for (appearance in matrixAppearances(MatrixTarget.DESKTOP) + matrixHighContrast) {
                for ((index, page) in matrixRowPages.withIndex()) {
                    runDesktopComposeUiTest(width = 360, height = 600) {
                        setContent { MatrixSurface(appearance) { MatrixEpisodePage(page) } }
                        waitForIdle()
                        onAllNodesWithText(page.first().row.title).fetchSemanticsNodes().forEach { node ->
                            check(node.boundsInRoot.top >= 0 && node.boundsInRoot.bottom <= 600) {
                                "${appearance.name} clips a row on page $index: ${node.boundsInRoot}"
                            }
                        }
                        val number = index.toString().padStart(2, '0')
                        onRoot().captureRoboImage("screenshots/desktop/m10-rows-p$number-${appearance.name}.png")
                    }
                }
                for (subject in MatrixSupplement.entries) {
                    runDesktopComposeUiTest(width = 360, height = 480) {
                        setContent { MatrixSurface(appearance) { MatrixSupplementContent(subject) } }
                        waitForIdle()
                        onRoot().captureRoboImage(
                            "screenshots/desktop/m10-${subject.name.lowercase()}-${appearance.name}.png",
                        )
                    }
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
