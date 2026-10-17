// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.SwipeAction
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpisodeSwipeHostTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun swipeCommitsOnlyOnReleasePastTheThreshold() {
        installFakeImageLoader()
        val actions = mutableListOf<EpisodeAction>()
        rule.setContent {
            CompositionLocalProvider(LocalPlatformKind provides PlatformKind.ANDROID) {
                NeutrodyneTheme(AppearancePrefs(dynamicColor = false), SystemUiState.DEFAULT) {
                    Box(Modifier.width(400.dp)) {
                        EpisodeRow(
                            testEpisodeRow(1),
                            null,
                            EpisodeRowStyle.FEED,
                            RowCaps.FULL.copy(swipe = SwipeConfig(SwipeAction.MARK_PLAYED, SwipeAction.MARK_PLAYED)),
                            highlightNew = false,
                            selected = null,
                            onAction = { actions += it },
                            modifier = Modifier.testTag("row"),
                        )
                    }
                }
            }
        }
        rule.onNodeWithTag("row").performTouchInput {
            down(Offset(width * .9f, height * .5f))
            moveTo(Offset(width * .6f, height * .5f), delayMillis = 200)
            up()
        }
        rule.runOnIdle { assertEquals(emptyList(), actions) }
        rule.onNodeWithTag("row").performTouchInput {
            down(Offset(width * .9f, height * .5f))
            moveTo(Offset(width * .2f, height * .5f), delayMillis = 200)
        }
        rule.runOnIdle { assertEquals(emptyList(), actions) }
        rule.onNodeWithTag("row").performTouchInput { up() }
        rule.runOnIdle {
            assertEquals(listOf<EpisodeAction>(EpisodeAction.SetPlayed(1, true, PlayedActionOrigin.SWIPE)), actions)
        }
    }
}
