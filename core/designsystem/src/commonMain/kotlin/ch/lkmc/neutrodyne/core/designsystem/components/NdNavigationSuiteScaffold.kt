// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarDefaults
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldLayout
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldState
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldValue
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp

/**
 * One row of the navigation suite (08 Destinations). [icon] is the outlined symbol; [iconSelected]
 * the filled variant used while the item is selected.
 */
public data class NdNavItem(
    val label: String,
    val icon: ImageVector,
    val iconSelected: ImageVector = icon,
    /** A dot badge (e.g. the downloads-failed count arrives as a labelled badge later). */
    val badge: Boolean = false,
    val testTag: String? = null,
)

/**
 * `NavigationSuiteScaffoldLayout` with our own `navigationSuite` for the rail types (08
 * Destinations): `WideNavigationRail` has a header slot but no footer, so the rail is a
 * `Box(fillMaxHeight)` with the five items on top and [footer] (the Settings gear) pinned
 * 16 dp above the navigation-bars inset, traversal-ordered after the last item. Bar types use the
 * stock `ShortNavigationBar`/`NavigationBar` and show no footer. The suite type is always the
 * library default for the window info, never forced.
 */
@Composable
public fun NdNavigationSuiteScaffold(
    items: List<NdNavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    suiteType: NavigationSuiteType = defaultSuiteType(),
    state: NavigationSuiteScaffoldState = rememberNavigationSuiteScaffoldState(),
    footer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // NavigationSuiteScaffoldLayout takes no modifier in m3 1.9.0, so it is applied to the wrapper.
    // The Surface gives every uncoloured content node the themed background and content colour
    // (08 Contrast: no black-on-default-black text in dark mode).
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        NavigationSuiteScaffoldLayout(
            navigationSuite = {
                when (suiteType) {
                    NavigationSuiteType.WideNavigationRailCollapsed,
                    NavigationSuiteType.WideNavigationRailExpanded,
                    NavigationSuiteType.NavigationRail,
                    -> NdRail(items, selected, onSelect, footer, suiteType)

                    else -> NdBar(items, selected, onSelect, suiteType)
                }
            },
            navigationSuiteType = suiteType,
            state = state,
        ) {
            // The stock scaffold consumes the suite's insets for the content; its helper is
            // private, so this re-implements it: bar types consume the bottom inset, rails the
            // start inset, nothing while the suite is hidden.
            Box(Modifier.fillMaxSize().consumeWindowInsets(suiteInsets(suiteType, state))) {
                content()
            }
        }
    }
}

/** The 1.4.0 default suite type for the current window (compact bar / short bar / wide rail). */
@Composable
public fun defaultSuiteType(): NavigationSuiteType =
    NavigationSuiteScaffoldDefaults.navigationSuiteType(
        currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true),
    )

@Composable
private fun NdRail(
    items: List<NdNavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    footer: (@Composable () -> Unit)?,
    suiteType: NavigationSuiteType,
) {
    val railExpanded = suiteType == NavigationSuiteType.WideNavigationRailExpanded
    val railState =
        rememberWideNavigationRailState(
            if (railExpanded) WideNavigationRailValue.Expanded else WideNavigationRailValue.Collapsed,
        )

    Box(Modifier.fillMaxHeight()) {
        WideNavigationRail(
            state = railState,
            header = null,
            arrangement = Arrangement.Top,
        ) {
            items.forEachIndexed { index, item ->
                WideNavigationRailItem(
                    selected = index == selected,
                    onClick = { onSelect(index) },
                    icon = {
                        Icon(
                            if (index == selected) item.iconSelected else item.icon,
                            contentDescription = null,
                        )
                    },
                    label = { Text(item.label) },
                    railExpanded = railExpanded,
                    modifier = itemModifier(item),
                )
            }
        }
        if (footer != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                    .padding(bottom = GEAR_BOTTOM_PADDING)
                    .semantics { traversalIndex = TRAVERSAL_AFTER_LAST },
            ) {
                footer()
            }
        }
    }
}

@Composable
private fun NdBar(
    items: List<NdNavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    suiteType: NavigationSuiteType,
) {
    if (suiteType == NavigationSuiteType.NavigationBar) {
        NavigationBar {
            items.forEachIndexed { index, item ->
                NavigationBarItem(
                    selected = index == selected,
                    onClick = { onSelect(index) },
                    icon = {
                        Icon(
                            if (index == selected) item.iconSelected else item.icon,
                            contentDescription = null,
                        )
                    },
                    label = { Text(item.label) },
                    modifier = itemModifier(item),
                )
            }
        }
    } else {
        ShortNavigationBar {
            items.forEachIndexed { index, item ->
                ShortNavigationBarItem(
                    selected = index == selected,
                    onClick = { onSelect(index) },
                    icon = {
                        Icon(
                            if (index == selected) item.iconSelected else item.icon,
                            contentDescription = null,
                        )
                    },
                    label = { Text(item.label) },
                    modifier = itemModifier(item),
                )
            }
        }
    }
}

private fun itemModifier(item: NdNavItem): Modifier =
    if (item.testTag != null) Modifier.testTag(item.testTag) else Modifier

@Composable
private fun suiteInsets(
    suiteType: NavigationSuiteType,
    state: NavigationSuiteScaffoldState,
): WindowInsets {
    if (state.currentValue == NavigationSuiteScaffoldValue.Hidden && !state.isAnimating) {
        return WindowInsets(0, 0, 0, 0)
    }
    return when (suiteType) {
        NavigationSuiteType.ShortNavigationBarCompact,
        NavigationSuiteType.ShortNavigationBarMedium,
        -> {
            ShortNavigationBarDefaults.windowInsets.only(WindowInsetsSides.Bottom)
        }

        NavigationSuiteType.WideNavigationRailCollapsed,
        NavigationSuiteType.WideNavigationRailExpanded,
        -> {
            WideNavigationRailDefaults.windowInsets.only(WindowInsetsSides.Start)
        }

        NavigationSuiteType.NavigationBar -> {
            NavigationBarDefaults.windowInsets.only(WindowInsetsSides.Bottom)
        }

        NavigationSuiteType.NavigationRail -> {
            NavigationRailDefaults.windowInsets.only(WindowInsetsSides.Start)
        }

        else -> {
            WindowInsets(0, 0, 0, 0)
        }
    }
}

private val GEAR_BOTTOM_PADDING = 16.dp

/** The gear must be traversed after the last destination (08 Destinations). */
private const val TRAVERSAL_AFTER_LAST: Float = 5f
