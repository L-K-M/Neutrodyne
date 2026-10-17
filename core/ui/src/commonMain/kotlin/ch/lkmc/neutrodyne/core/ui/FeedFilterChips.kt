// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdFilterChip
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.FeedFilterCapability
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.withCapabilities
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.feeds_clear_filters
import ch.lkmc.neutrodyne.core.ui.resources.filter_downloaded
import ch.lkmc.neutrodyne.core.ui.resources.filter_in_progress
import ch.lkmc.neutrodyne.core.ui.resources.filter_unplayed
import org.jetbrains.compose.resources.stringResource

/**
 * 08 filter toggles and Clear, limited to the caller's available capabilities. Media, sort and
 * age controls live beside this row in the owning screen. Chips never choose the group (D55).
 */
@Composable
public fun FeedFilterChips(
    filters: FeedFilters,
    onFiltersChange: (FeedFilters) -> Unit,
    availableFilters: Set<FeedFilterCapability>,
    modifier: Modifier = Modifier,
) {
    val effective = filters.withCapabilities(availableFilters)
    val anyActive =
        effective.unplayedOnly || effective.downloadedOnly || effective.inProgressOnly ||
            effective.media != MediaFilter.ALL ||
            effective.minSortDate != null

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
        contentPadding = PaddingValues(horizontal = CHIP_ROW_PADDING),
    ) {
        item(key = "unplayed") {
            NdFilterChip(
                selected = effective.unplayedOnly,
                onClick = { onFiltersChange(effective.copy(unplayedOnly = !effective.unplayedOnly)) },
                label = stringResource(Res.string.filter_unplayed),
                leadingIcon = if (effective.unplayedOnly) NdIcons.Check else null,
            )
        }
        if (FeedFilterCapability.DOWNLOADED in availableFilters) {
            item(key = "downloaded") {
                NdFilterChip(
                    selected = effective.downloadedOnly,
                    onClick = { onFiltersChange(effective.copy(downloadedOnly = !effective.downloadedOnly)) },
                    label = stringResource(Res.string.filter_downloaded),
                    leadingIcon = if (effective.downloadedOnly) NdIcons.Check else null,
                )
            }
        }
        if (FeedFilterCapability.IN_PROGRESS in availableFilters) {
            item(key = "inProgress") {
                NdFilterChip(
                    selected = effective.inProgressOnly,
                    onClick = { onFiltersChange(effective.copy(inProgressOnly = !effective.inProgressOnly)) },
                    label = stringResource(Res.string.filter_in_progress),
                    leadingIcon = if (effective.inProgressOnly) NdIcons.Check else null,
                )
            }
        }
        if (anyActive) {
            item(key = "clear") {
                NdFilterChip(
                    selected = false,
                    onClick = { onFiltersChange(FeedFilters()) },
                    label = stringResource(Res.string.feeds_clear_filters),
                    leadingIcon = NdIcons.Close,
                )
            }
        }
    }
}

private val CHIP_GAP = 8.dp
private val CHIP_ROW_PADDING = 16.dp
