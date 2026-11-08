// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.paging.PagingData
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportItemView
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportSessionView
import ch.lkmc.neutrodyne.core.model.ImportState
import kotlinx.coroutines.flow.flowOf

@Composable
internal fun M10ScreenContent(variant: Int) {
    val status = if (variant == 0) ImportItemStatus.PREVIEW else ImportItemStatus.SUBSCRIBED
    val item =
        ImportItemView(
            0,
            "A podcast to import",
            "example.com",
            ImportItemKind.RSS,
            status,
            true,
            true,
            emptyList(),
            false,
            null,
            null,
        )
    val session =
        ImportSessionView(
            1,
            "subscriptions.opml",
            ImportFormat.OPML,
            if (variant == 0) ImportState.PREVIEW else ImportState.DONE,
            false,
            emptyList(),
            ImportOptions(),
            mapOf(status to 1),
            1,
            0,
            0,
        )
    ImportScreen(
        ImportUiState(loaded = true, session = session),
        remember(variant) { flowOf(PagingData.from(listOf(item))) },
        {},
        {},
        { _, _ -> },
        {},
        {},
        {},
        { _, _ -> },
        { _, _ -> null },
        {},
        { null },
        {},
        {},
        {},
        {},
        {},
        {},
    )
}
