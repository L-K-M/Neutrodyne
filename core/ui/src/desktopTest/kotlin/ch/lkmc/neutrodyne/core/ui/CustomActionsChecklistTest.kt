// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.LibraryTile
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.testFeedHealth
import ch.lkmc.neutrodyne.core.testing.testMonogramArtwork
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CustomActionsChecklistTest {
    @Test fun coverTileIncludesOpenAndLongPressSelection() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            installFakeImageLoader()
            runDesktopComposeUiTest(width = 240, height = 300) {
                setContent {
                    MatrixSurface(MatrixAppearance("light", 1f)) {
                        CoverTile(
                            LibraryTile(
                                1,
                                "Test podcast",
                                SourceType.RSS,
                                PodcastStatus.ACTIVE,
                                testMonogramArtwork("test"),
                                null,
                                testFeedHealth(),
                                null,
                                1,
                                0,
                            ),
                            false,
                            null,
                            emptyList(),
                            {},
                            {},
                            Modifier.size(152.dp),
                        )
                    }
                }
                val actions =
                    onNode(
                        hasClickAction(),
                    ).fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
                assertTrue("Open" in actions, "Missing Open custom action: $actions")
                assertTrue("Select" in actions, "Long press has no Select custom action: $actions")
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun everyEpisodeStateKeepsSelectionAndPlayedActions() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            installFakeImageLoader()
            for (state in matrixRows) {
                runDesktopComposeUiTest(width = 360, height = 300) {
                    setContent { MatrixSurface(MatrixAppearance("light", 1f)) { MatrixEpisodeRow(state) } }
                    val actions =
                        onNodeWithText(
                            state.row.title,
                        ).fetchSemanticsNode().config[SemanticsActions.CustomActions].map {
                            it.label
                        }
                    assertTrue("Select" in actions, state.name)
                    assertTrue("Open" in actions, state.name)
                    assertTrue(
                        (
                            if (state.row.playedAt ==
                                null
                            ) {
                                "Mark played"
                            } else {
                                "Mark unplayed"
                            }
                        ) in actions,
                        state.name,
                    )
                    assertTrue("Play next" in actions, state.name)
                    assertTrue("Play last" in actions, state.name)
                    assertTrue("Go to podcast" in actions, state.name)
                    val download =
                        when (state.live?.downloadState ?: state.row.downloadState) {
                            null -> "Download"

                            // M6a exposes promote and cancel for queued rows behind the options menu.
                            DownloadState.QUEUED -> "Download actions"

                            DownloadState.RESOLVING -> "Cancel download"

                            DownloadState.DOWNLOADING -> "Pause download"

                            DownloadState.PAUSED -> "Resume download"

                            DownloadState.FAILED, DownloadState.MISSING -> "Retry download"

                            DownloadState.COMPLETED -> "Delete download"

                            DownloadState.VERIFYING -> null
                        }
                    if (state.caps.downloads && download != null) assertTrue(download in actions, state.name)
                    assertTrue(actions.distinct().size == actions.size, "Duplicate actions: ${state.name} $actions")
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
