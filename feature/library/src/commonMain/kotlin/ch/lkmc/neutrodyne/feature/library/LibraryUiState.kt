// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.library

import androidx.compose.runtime.Immutable
import ch.lkmc.neutrodyne.core.model.FeedCounts
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupMosaicMember
import ch.lkmc.neutrodyne.core.model.LibraryTile
import ch.lkmc.neutrodyne.core.model.settings.LibraryDensity
import ch.lkmc.neutrodyne.core.model.settings.LibrarySegment
import ch.lkmc.neutrodyne.core.model.settings.LibrarySort
import ch.lkmc.neutrodyne.core.ui.UserMessage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

internal const val UNGROUPED_FILTER = "ungrouped"

/** Library tiles, ordered groups and the persisted single-select filter (08 Library). */
@Immutable
public data class LibraryUiState(
    val tiles: ImmutableList<LibraryTile> = persistentListOf(),
    val groups: ImmutableList<Group> = persistentListOf(),
    val mosaics: ImmutableMap<Long, ImmutableList<GroupMosaicMember>> = persistentMapOf(),
    val groupCounts: ImmutableMap<Long, FeedCounts> = persistentMapOf(),
    val segment: LibrarySegment = LibrarySegment.PODCASTS,
    val groupFilter: String = "",
    val loaded: Boolean = false,
    val showTitles: Boolean = false,
    val density: LibraryDensity = LibraryDensity.MEDIUM,
    val sort: LibrarySort = LibrarySort.TITLE,
    val offline: Boolean = false,
    val messages: ImmutableList<UserMessage> = persistentListOf(),
)
