// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.lkmc.neutrodyne.core.designsystem.components.NdBanner
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.offline_banner
import org.jetbrains.compose.resources.stringResource

/**
 * The "you're offline" banner (08 Banners: feeds keep working from the database). Shows nothing
 * while online.
 */
@Composable
public fun OfflineBanner(
    offline: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!offline) return
    NdBanner(
        message = stringResource(Res.string.offline_banner),
        icon = NdIcons.CloudOff,
        modifier = modifier,
    )
}

/** [OfflineBanner] as the first item of a screen's `LazyColumn` (08's banner placement). */
public fun LazyListScope.offlineBannerItem(offline: Boolean) {
    if (!offline) return
    item(key = "offlineBanner", contentType = "banner") { OfflineBanner(offline = true) }
}
