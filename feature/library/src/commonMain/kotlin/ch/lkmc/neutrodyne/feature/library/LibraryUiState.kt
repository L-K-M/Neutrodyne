// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.library

import androidx.compose.runtime.Immutable
import ch.lkmc.neutrodyne.core.model.LibraryTile
import ch.lkmc.neutrodyne.core.model.settings.LibrarySort
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * The Library grid's M1a state (08 Library): [tiles] are already sorted by [sort]; [loaded]
 * separates "12 skeleton tiles" from the empty-library onboarding. Group chips, selection mode
 * and banners beyond the offline one arrive with M2.
 */
@Immutable
public data class LibraryUiState(
    val tiles: ImmutableList<LibraryTile> = persistentListOf(),
    val loaded: Boolean = false,
    val showTitles: Boolean = false,
    val sort: LibrarySort = LibrarySort.TITLE,
    val offline: Boolean = false,
)
