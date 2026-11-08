// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.testing.screenshots.ScreenSnapshot
import io.github.takahirom.roborazzi.captureRoboImage
import java.util.Locale
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class M10ScreenScreenshotTest {
    @Test fun sharedScreenInBothThemes() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            installFakeImageLoader()
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                for (variant in 0 until 2) {
                    runDesktopComposeUiTest(width = 900, height = 800) {
                        setContent { ScreenSnapshot(dark, PlatformKind.DESKTOP) { M10ScreenContent(variant) } }
                        waitForIdle()
                        onRoot().captureRoboImage(
                            "screenshots/desktop/m10-screen-$variant-$theme.png",
                        )
                    }
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
