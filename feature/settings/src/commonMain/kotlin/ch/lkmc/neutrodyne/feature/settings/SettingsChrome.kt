// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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

/**
 * One Settings row (08 Settings): leading symbol, title, one-line [summary] and a trailing
 * chevron. The whole row is the click target.
 */
@Composable
internal fun SettingsRow(
    icon: ImageVector,
    title: String,
    summary: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { if (summary != null) Text(summary) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(NdIcons.ArrowForwardIos, contentDescription = null) },
        modifier =
            modifier
                .clickable(onClick = onClick)
                .semantics { role = Role.Button },
    )
}

/**
 * A switch row (08 Settings: `NdSwitchRow` with the current state as subtitle). The whole row is
 * the toggle target, so touch targets stay accessible.
 */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { if (summary != null) Text(summary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier =
            modifier
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ),
    )
}
