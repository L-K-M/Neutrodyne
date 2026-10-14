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
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.feeds_clear_filters
import ch.lkmc.neutrodyne.core.ui.resources.filter_downloaded
import ch.lkmc.neutrodyne.core.ui.resources.filter_in_progress
import ch.lkmc.neutrodyne.core.ui.resources.filter_unplayed
import org.jetbrains.compose.resources.stringResource

/**
 * 08 "Filter chips": Unplayed / Downloaded / In progress toggles plus a "Clear" chip while any
 * filter is active. The media and sort dropdowns and the "Last 30 days" chip arrive with the
 * screens that set them (M2+); at M1a the All feed's order is fixed NEWEST_FIRST, so no sort chip
 * renders here. Chips only filter — they never choose the group (D55).
 */
@Composable
public fun FeedFilterChips(
    filters: FeedFilters,
    onFiltersChange: (FeedFilters) -> Unit,
    modifier: Modifier = Modifier,
) {
    val anyActive =
        filters.unplayedOnly || filters.downloadedOnly || filters.inProgressOnly ||
            filters.media != MediaFilter.ALL ||
            filters.minSortDate != null

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
        contentPadding = PaddingValues(horizontal = CHIP_ROW_PADDING),
    ) {
        item(key = "unplayed") {
            NdFilterChip(
                selected = filters.unplayedOnly,
                onClick = { onFiltersChange(filters.copy(unplayedOnly = !filters.unplayedOnly)) },
                label = stringResource(Res.string.filter_unplayed),
                leadingIcon = if (filters.unplayedOnly) NdIcons.Check else null,
            )
        }
        item(key = "downloaded") {
            NdFilterChip(
                selected = filters.downloadedOnly,
                onClick = { onFiltersChange(filters.copy(downloadedOnly = !filters.downloadedOnly)) },
                label = stringResource(Res.string.filter_downloaded),
                leadingIcon = if (filters.downloadedOnly) NdIcons.Check else null,
            )
        }
        item(key = "inProgress") {
            NdFilterChip(
                selected = filters.inProgressOnly,
                onClick = { onFiltersChange(filters.copy(inProgressOnly = !filters.inProgressOnly)) },
                label = stringResource(Res.string.filter_in_progress),
                leadingIcon = if (filters.inProgressOnly) NdIcons.Check else null,
            )
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
