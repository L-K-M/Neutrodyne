// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.runtime.Composable
import ch.lkmc.neutrodyne.core.designsystem.components.NdTooltipIconButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.back
import org.jetbrains.compose.resources.stringResource

/** The pushed screens' back affordance (08 Top bars): pops the selected tab's stack. */
@Composable
public fun NavBackButton() {
    val navigator = LocalAppNavigator.current
    NdTooltipIconButton(
        onClick = { navigator.pop() },
        icon = NdIcons.ArrowBack,
        tooltip = stringResource(Res.string.back),
    )
}
