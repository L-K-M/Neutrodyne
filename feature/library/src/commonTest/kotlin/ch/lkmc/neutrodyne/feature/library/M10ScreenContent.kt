// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.settings.LibrarySegment
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.testLibraryTile
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.runBlocking

@Composable
internal fun M10ScreenContent(variant: Int) {
    val groups =
        remember {
            FakeGroupRepository()
                .also { repository ->
                    runBlocking {
                        repository.create(GroupDraft("Technology"))
                        repository.create(GroupDraft("Stories"))
                    }
                }.groups.value
                .toImmutableList()
        }
    val state =
        LibraryUiState(
            loaded = true,
            showTitles = true,
            groups = groups,
            tiles = (1L..12).map { testLibraryTile(it) }.toImmutableList(),
            segment = if (variant == 0) LibrarySegment.PODCASTS else LibrarySegment.GROUPS,
        )
    LibraryScreen(state, null, null, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {})
}
