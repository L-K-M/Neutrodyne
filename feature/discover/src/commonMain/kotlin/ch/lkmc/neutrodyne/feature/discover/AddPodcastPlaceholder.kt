// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.discover

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.lkmc.neutrodyne.core.designsystem.components.NdEmptyState
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.add_title
import ch.lkmc.neutrodyne.feature.discover.resources.discover_arrives_later
import org.jetbrains.compose.resources.stringResource
import ch.lkmc.neutrodyne.feature.discover.resources.Res as DiscoverRes

/**
 * M1a placeholder for the `AddPodcastKey` bottom sheet the add-podcast flow (#95) replaces: the
 * Library's empty state and "Add by URL" push this key today, and nav3's provider crashes on a
 * key no module contributes — so the placeholder only needs to keep navigation alive.
 */
@Composable
internal fun AddPodcastPlaceholder() {
    val title = stringResource(Res.string.add_title)
    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(title = title)
        NdEmptyState(
            icon = NdIcons.Add,
            title = title,
            body = stringResource(DiscoverRes.string.discover_arrives_later),
        )
    }
}
