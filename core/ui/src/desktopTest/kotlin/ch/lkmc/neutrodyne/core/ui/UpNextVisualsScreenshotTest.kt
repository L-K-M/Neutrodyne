// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import coil3.test.FakeImage
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class UpNextVisualsScreenshotTest {
    @Test
    fun queueStates() {
        installFakeImageLoader(
            queueVisualArtwork.associateWith {
                FakeImage(
                    QUEUE_ARTWORK_SIDE_PX,
                    QUEUE_ARTWORK_SIDE_PX,
                    color = queueArtworkColor,
                )
            },
        )
        for (visual in QueueVisual.entries) {
            runDesktopComposeUiTest(width = QUEUE_DESKTOP_WIDTH_DP, height = QUEUE_CAPTURE_HEIGHT_DP) {
                setContent {
                    NeutrodyneTheme(
                        AppearancePrefs(dynamicColor = false),
                        SystemUiState(
                            dark = visual.appearance != QueueAppearance.LIGHT,
                            contrast = 0.0,
                            reducedMotion = true,
                        ),
                    ) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            QueueVisualContent(visual)
                        }
                    }
                }
                waitForIdle()
                onRoot().captureRoboImage("screenshots/desktop/queue_${visual.name.lowercase()}.png")
            }
        }
    }
}
