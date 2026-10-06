// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ch.lkmc.neutrodyne.core.designsystem.components.NdTooltipIconButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.settings
import ch.lkmc.neutrodyne.core.ui.resources.settings_badge
import org.jetbrains.compose.resources.stringResource

/**
 * The Settings gear (08 Destinations): the rail footer's and top bars' entry into
 * `SettingsHomeKey`, pushed on the selected tab's stack. [badge] defaults to [LocalSettingsBadge]
 * (the update-available dot, M11a); the badge is announced through [badgeDescription].
 */
@Composable
public fun SettingsGearButton(
    modifier: Modifier = Modifier,
    badge: Boolean = LocalSettingsBadge.current,
    badgeDescription: String = stringResource(Res.string.settings_badge),
    onClick: (() -> Unit)? = null,
) {
    val navigator = LocalAppNavigator.current
    val tooltip = stringResource(Res.string.settings)
    BadgedBox(
        badge = {
            if (badge) {
                Badge(
                    Modifier.semantics { contentDescription = badgeDescription },
                )
            }
        },
        modifier = modifier,
    ) {
        NdTooltipIconButton(
            onClick = onClick ?: { navigator.push(SettingsHomeKey) },
            icon = NdIcons.Settings,
            tooltip = tooltip,
        )
    }
}
