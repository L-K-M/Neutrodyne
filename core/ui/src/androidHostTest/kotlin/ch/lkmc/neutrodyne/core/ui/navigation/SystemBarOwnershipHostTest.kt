// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import ch.lkmc.neutrodyne.core.designsystem.PlatformSystemBarAppearance
import ch.lkmc.neutrodyne.core.designsystem.StatusBarAppearance
import ch.lkmc.neutrodyne.core.designsystem.SystemBarOwner
import ch.lkmc.neutrodyne.core.designsystem.statusBarAppearanceFor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemBarOwnershipHostTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun disposalRestoresWindowFlags() {
        val shown = mutableStateOf(true)
        val controller = WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView)
        rule.runOnUiThread {
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = true
        }
        rule.setContent {
            if (shown.value) PlatformSystemBarAppearance(StatusBarAppearance.DarkIcons)
        }
        rule.runOnIdle { assertTrue(controller.isAppearanceLightStatusBars) }
        rule.runOnIdle { shown.value = false }
        rule.runOnIdle {
            assertFalse(controller.isAppearanceLightStatusBars)
            assertTrue(controller.isAppearanceLightNavigationBars)
        }
    }

    @Test
    fun headerChangesStatusOnlyAndRestoresTheCurrentAppTheme() {
        val header = mutableStateOf(true)
        val dark = mutableStateOf(false)
        val controller = WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView)
        rule.setContent {
            PlatformSystemBarAppearance(
                if (dark.value) StatusBarAppearance.LightIcons else StatusBarAppearance.DarkIcons,
            )
            if (header.value) PlatformSystemBarAppearance(StatusBarAppearance.LightIcons, SystemBarOwner.Header)
        }
        rule.runOnIdle {
            assertFalse(controller.isAppearanceLightStatusBars)
            assertTrue(controller.isAppearanceLightNavigationBars)
            dark.value = true
        }
        rule.runOnIdle {
            assertFalse(controller.isAppearanceLightStatusBars)
            assertFalse(controller.isAppearanceLightNavigationBars)
            header.value = false
        }
        rule.runOnIdle { assertFalse(controller.isAppearanceLightStatusBars) }
        rule.runOnIdle { dark.value = false }
        rule.runOnIdle { assertTrue(controller.isAppearanceLightStatusBars) }
    }

    @Test
    fun playerOwnsStatusAcrossHeaderUpdatesAndOutOfOrderDisposal() {
        val header = mutableStateOf(true)
        val player = mutableStateOf(true)
        val headerColor = mutableStateOf(Color.White)
        val controller = WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView)
        rule.setContent {
            PlatformSystemBarAppearance(StatusBarAppearance.DarkIcons)
            if (header.value) {
                PlatformSystemBarAppearance(
                    statusBarAppearanceFor(headerColor.value),
                    SystemBarOwner.Header,
                )
            }
            if (player.value) PlatformSystemBarAppearance(StatusBarAppearance.LightIcons, SystemBarOwner.Player)
        }
        rule.runOnIdle {
            headerColor.value = Color.Black
            assertFalse(controller.isAppearanceLightStatusBars)
        }
        rule.runOnIdle { headerColor.value = Color.White }
        rule.runOnIdle {
            assertFalse(controller.isAppearanceLightStatusBars, "updating a covered header stole the player lease")
            assertTrue(controller.isAppearanceLightNavigationBars)
            header.value = false
        }
        rule.runOnIdle {
            assertFalse(controller.isAppearanceLightStatusBars, "disposing a covered header stole the player lease")
            player.value = false
        }
        rule.runOnIdle { assertTrue(controller.isAppearanceLightStatusBars) }
    }

    @Test
    fun playerCollapseRestoresLiveHeaderInLightDarkAndBlack() {
        val player = mutableStateOf(true)
        val color = mutableStateOf(Color.White)
        val controller = WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView)
        rule.setContent {
            PlatformSystemBarAppearance(StatusBarAppearance.DarkIcons)
            PlatformSystemBarAppearance(statusBarAppearanceFor(color.value), SystemBarOwner.Header)
            if (player.value) PlatformSystemBarAppearance(StatusBarAppearance.LightIcons, SystemBarOwner.Player)
        }
        for (background in listOf(Color.White, Color(0xFF202020), Color.Black)) {
            rule.runOnIdle {
                player.value = true
                color.value = background
            }
            rule.runOnIdle {
                assertFalse(controller.isAppearanceLightStatusBars)
                player.value = false
            }
            rule.runOnIdle {
                assertEquals(background == Color.White, controller.isAppearanceLightStatusBars)
                assertTrue(controller.isAppearanceLightNavigationBars)
            }
        }
    }
}
