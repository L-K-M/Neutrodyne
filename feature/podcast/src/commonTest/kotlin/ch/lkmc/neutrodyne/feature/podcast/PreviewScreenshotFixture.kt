// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.podcast

import androidx.compose.runtime.Composable
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.model.PreviewEpisode
import ch.lkmc.neutrodyne.core.testing.testFeedPreview

internal enum class PreviewShot { LOADING, ERROR, CONTENT }

@Composable
internal fun PreviewScreenshotFixture(shot: PreviewShot) {
    val state =
        when (shot) {
            PreviewShot.LOADING -> {
                PodcastPreviewState()
            }

            PreviewShot.ERROR -> {
                PodcastPreviewState(loading = false, fetchError = AddPodcastError.NotAFeed)
            }

            PreviewShot.CONTENT -> {
                val preview =
                    testFeedPreview().copy(
                        episodes =
                            listOf(
                                PreviewEpisode("Morning report", null, null, "A short episode summary.", null, false),
                            ),
                    )
                PodcastPreviewState(loading = false, preview = preview)
            }
        }
    PodcastPreviewScreen(state, {}, {}, {}, {}, {}, {}, {})
}
