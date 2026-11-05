// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NavigationLabelScaleTest {
    @Test fun allFiveLabelsStayReadableAtTwoHundredPercent() {
        val labels = listOf("Feeds", "Bibliothek", "Als Nächstes", "Downloads", "Entdecken")
        for (type in listOf(
            NavigationSuiteType.ShortNavigationBarCompact,
            NavigationSuiteType.ShortNavigationBarMedium,
            NavigationSuiteType.WideNavigationRailCollapsed,
        )) {
            runDesktopComposeUiTest(width = 360, height = 800) {
                setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                        NeutrodyneTheme(AppearancePrefs(dynamicColor = false), SystemUiState.DEFAULT) {
                            NdNavigationSuiteScaffold(
                                labels.map { NdNavItem(it, NdIcons.Podcasts) },
                                0,
                                {},
                                suiteType = type,
                            ) {}
                        }
                    }
                }
                for (label in labels) {
                    val node = onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
                    val results = mutableListOf<TextLayoutResult>()
                    assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results), label)
                    assertFalse(results.single().hasVisualOverflow, "$type clips $label at 200 %: ${results.single()}")
                    for (line in 0 until results.single().lineCount) {
                        assertFalse(results.single().isLineEllipsized(line), "$type truncates $label")
                    }
                    assertTrue(node.boundsInRoot.top >= 0 && node.boundsInRoot.bottom <= 800, "$type hides $label")
                }
            }
        }
    }
}
