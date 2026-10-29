// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import coil3.test.FakeImage
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class UpNextVisualsScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun fakeImages() {
        installFakeImageLoader(
            queueVisualArtwork.associateWith {
                FakeImage(
                    QUEUE_ARTWORK_SIDE_PX,
                    QUEUE_ARTWORK_SIDE_PX,
                    color = queueArtworkColor,
                )
            },
        )
    }

    @Test fun empty() = capture(QueueVisual.EMPTY)

    @Test fun currentOnly() = capture(QueueVisual.CURRENT_ONLY)

    @Test fun content() = capture(QueueVisual.CONTENT)

    @Test fun external() = capture(QueueVisual.EXTERNAL)

    @Test fun contentDark() = capture(QueueVisual.CONTENT_DARK)

    @Test fun emptyDark() = capture(QueueVisual.EMPTY_DARK)

    @Test fun currentOnlyDark() = capture(QueueVisual.CURRENT_ONLY_DARK)

    @Test fun externalDark() = capture(QueueVisual.EXTERNAL_DARK)

    @Test
    @Config(qualifiers = "ar-rXB-w360dp-h640dp-mdpi")
    fun contentStress() = capture(QueueVisual.CONTENT_STRESS)

    private fun capture(visual: QueueVisual) {
        rule.setContent {
            NeutrodyneTheme(
                AppearancePrefs(dynamicColor = false),
                SystemUiState(dark = visual.appearance != QueueAppearance.LIGHT, contrast = 0.0, reducedMotion = true),
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    QueueVisualContent(visual)
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("screenshots/android/queue_${visual.name.lowercase()}.png")
    }
}
