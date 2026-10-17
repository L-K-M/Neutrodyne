// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.platform

import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.notifications_blocked
import ch.lkmc.neutrodyne.core.ui.resources.notifications_open_settings
import org.jetbrains.compose.resources.stringResource

/** Null requesters hide permission controls on desktop and Android before API 33. */
@Composable
public fun NotificationPermissionNotice(
    enabled: Boolean,
    requester: NotificationPermissionRequester?,
    modifier: Modifier = Modifier,
) {
    if (!enabled || requester == null || requester.granted) return

    ListItem(
        modifier = modifier,
        headlineContent = { Text(stringResource(Res.string.notifications_blocked)) },
        trailingContent = {
            TextButton(onClick = requester::openSettings) {
                Text(stringResource(Res.string.notifications_open_settings))
            }
        },
    )
}
