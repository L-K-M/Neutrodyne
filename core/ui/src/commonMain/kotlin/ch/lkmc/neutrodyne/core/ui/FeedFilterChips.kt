// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import ch.lkmc.neutrodyne.core.designsystem.radio.PianoKey
import ch.lkmc.neutrodyne.core.designsystem.radio.PianoKeySlot
import ch.lkmc.neutrodyne.core.model.FeedFilterCapability
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.withCapabilities
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.feeds_clear_filters
import ch.lkmc.neutrodyne.core.ui.resources.filter_clear_key
import ch.lkmc.neutrodyne.core.ui.resources.filter_downloaded
import ch.lkmc.neutrodyne.core.ui.resources.filter_in_progress
import ch.lkmc.neutrodyne.core.ui.resources.filter_unplayed
import org.jetbrains.compose.resources.stringResource

/**
 * 08 filter toggles and Clear, limited to the caller's available capabilities, as a bank of
 * latching piano keys (PO-55): each filter key stays pressed in with its lamp lit while on, and
 * the Clear key releases them all; it is dimmed while nothing is pressed. The bank scrolls
 * sideways when long labels do not fit. Media, sort and age controls live beside this bank in
 * the owning screen. Keys never choose the group (D55).
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

    PianoKeySlot(modifier.horizontalScroll(rememberScrollState())) {
        PianoKey(
            stringResource(Res.string.filter_unplayed),
            pressed = effective.unplayedOnly,
            onClick = { onFiltersChange(effective.copy(unplayedOnly = !effective.unplayedOnly)) },
            role = Role.Checkbox,
        )
        if (FeedFilterCapability.DOWNLOADED in availableFilters) {
            PianoKey(
                stringResource(Res.string.filter_downloaded),
                pressed = effective.downloadedOnly,
                onClick = { onFiltersChange(effective.copy(downloadedOnly = !effective.downloadedOnly)) },
                role = Role.Checkbox,
            )
        }
        if (FeedFilterCapability.IN_PROGRESS in availableFilters) {
            PianoKey(
                stringResource(Res.string.filter_in_progress),
                pressed = effective.inProgressOnly,
                onClick = { onFiltersChange(effective.copy(inProgressOnly = !effective.inProgressOnly)) },
                role = Role.Checkbox,
            )
        }
        PianoKey(
            stringResource(Res.string.filter_clear_key),
            spokenLabel = stringResource(Res.string.feeds_clear_filters),
            pressed = false,
            onClick = { onFiltersChange(FeedFilters()) },
            role = Role.Button,
            enabled = anyActive,
        )
    }
}
