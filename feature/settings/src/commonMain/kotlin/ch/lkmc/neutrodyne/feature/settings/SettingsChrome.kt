// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.runtime.Composable
import ch.lkmc.neutrodyne.core.designsystem.components.NdTooltipIconButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.back
import org.jetbrains.compose.resources.stringResource

/** The back affordance of every pushed Settings page: pops the tab's stack. */
@Composable
internal fun SettingsBackButton() {
    val navigator = LocalAppNavigator.current
    NdTooltipIconButton(
        onClick = { navigator.pop() },
        icon = NdIcons.ArrowBack,
        tooltip = stringResource(Res.string.back),
    )
}
