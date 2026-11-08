// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.discover.add

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.lkmc.neutrodyne.core.designsystem.components.NdModalBottomSheet
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.testFeedPreview
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AddSheetSemanticsDumpTest {
    @BeforeTest
    fun setup() {
        Locale.setDefault(Locale.ENGLISH)
        installFakeImageLoader()
    }

    @Test
    fun dump() =
        runDesktopComposeUiTest(width = 320, height = 640) {
            setContent {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    NdModalBottomSheet(onDismissRequest = {}) {
                        AddPodcastSheet(
                            initialInput = "https://example.com/feed",
                            state =
                                AddPodcastUiState(
                                    step = AddSheetStep.Preview(testFeedPreview(title = "M7 Subscribe Journey")),
                                ),
                            onResolve = {},
                            onAutoResolve = {},
                            onCredentials = {},
                            onCancelResolve = {},
                            onInputChange = {},
                            onCandidate = {},
                            onSubscribe = {},
                            onOpenPodcast = {},
                            onDismiss = {},
                        )
                    }
                }
            }
            val nodes =
                onAllNodes(SemanticsMatcher("all") { true }, useUnmergedTree = true).fetchSemanticsNodes()
            for (node in nodes) {
                val b = node.boundsInRoot
                val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(separator = "|")
                val cd = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(separator = "|")
                val clickable = node.config.getOrNull(SemanticsActions.OnClick) != null
                println(
                    "NODE id=${node.id} x=${b.left} y=${b.top} w=${b.width} h=${b.height} " +
                        "text=$text cd=$cd click=$clickable",
                )
            }
        }
}
