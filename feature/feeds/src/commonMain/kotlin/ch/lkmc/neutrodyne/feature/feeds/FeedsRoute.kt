// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.lkmc.neutrodyne.core.designsystem.components.NdEmptyState
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.feeds_empty_body
import ch.lkmc.neutrodyne.core.ui.resources.feeds_empty_title
import ch.lkmc.neutrodyne.core.ui.resources.nav_feeds
import ch.lkmc.neutrodyne.core.ui.root.SettingsGearButton
import org.jetbrains.compose.resources.stringResource

/**
 * The M0a Feeds destination (01 M0 checklist step 16): a top bar with the Settings gear and the
 * empty state. M1 delivers the All feed and the group pager (08 Feeds).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeedsRoute() {
    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(
            title = stringResource(Res.string.nav_feeds),
            actions = { SettingsGearButton() },
        )
        NdEmptyState(
            icon = NdIcons.DynamicFeed,
            title = stringResource(Res.string.feeds_empty_title),
            body = stringResource(Res.string.feeds_empty_body),
        )
    }
}
