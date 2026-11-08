// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator

@Composable
internal fun M10ScreenContent(variant: Int) {
    DiscoverScreen(DiscoverState(), {}, {}, LocalAppNavigator.current)
}
