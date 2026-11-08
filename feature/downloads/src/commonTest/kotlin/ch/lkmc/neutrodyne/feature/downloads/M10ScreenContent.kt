// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.downloads

import androidx.compose.runtime.Composable
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.testing.testDownloadEntry
import ch.lkmc.neutrodyne.download.api.DownloadsOverview
import ch.lkmc.neutrodyne.download.api.StorageUsage

@Composable
internal fun M10ScreenContent(variant: Int) {
    val storage = StorageUsage("dir:default", 0, 0, 0, null, null, false)
    val state =
        DownloadsUiState(
            overview =
                DownloadsOverview(
                    inProgress = listOf(testDownloadEntry(1, DownloadState.DOWNLOADING)),
                    completed = (2L..9L).map { testDownloadEntry(it, DownloadState.COMPLETED) },
                    failed = emptyList(),
                    storage = storage,
                    notices = emptySet(),
                ),
            storage = storage,
        )
    DownloadsScreen(
        state,
        emptyMap(),
        {},
        {},
        {},
        { _, _ -> },
        {},
        {},
        {},
        null,
        {},
        {},
        {},
        {},
        {},
        {},
        {},
        {},
        {},
        {},
        {},
    )
}
