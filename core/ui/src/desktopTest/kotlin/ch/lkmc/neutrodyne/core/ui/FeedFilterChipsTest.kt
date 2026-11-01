// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import ch.lkmc.neutrodyne.core.model.AVAILABLE_FEED_FILTER_CAPABILITIES
import ch.lkmc.neutrodyne.core.model.FeedFilterCapability
import ch.lkmc.neutrodyne.core.model.FeedFilters
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FeedFilterChipsTest {
    @Test
    fun capabilitiesEnableEachFilterIndependently() =
        runComposeUiTest {
            val previous = Locale.getDefault()
            Locale.setDefault(Locale.ENGLISH)
            try {
                val available = androidx.compose.runtime.mutableStateOf(setOf(FeedFilterCapability.IN_PROGRESS))
                var changed: FeedFilters? = null
                setContent {
                    FeedFilterChips(
                        FeedFilters(downloadedOnly = true, inProgressOnly = true),
                        { changed = it },
                        available.value,
                    )
                }
                onNodeWithText("Downloaded").assertDoesNotExist()
                onNodeWithText("In progress").assertIsSelected().performClick()
                assertEquals(FeedFilters(), changed)

                runOnIdle { available.value = setOf(FeedFilterCapability.DOWNLOADED) }
                onNodeWithText("In progress").assertDoesNotExist()
                onNodeWithText("Downloaded").assertIsSelected().performClick()
                assertEquals(FeedFilters(), changed)

                runOnIdle { available.value = FeedFilterCapability.entries.toSet() }
                onNodeWithText("Downloaded").assertIsSelected()
                onNodeWithText("In progress").assertIsSelected()
                onNodeWithText("Clear filters").performClick()
                assertEquals(FeedFilters(), changed)
            } finally {
                Locale.setDefault(previous)
            }
        }

    @Test
    fun progressAndDownloadsAreAvailableWithTheirProducers() =
        runComposeUiTest {
            val previous = Locale.getDefault()
            Locale.setDefault(Locale.ENGLISH)
            try {
                setContent {
                    FeedFilterChips(
                        FeedFilters(downloadedOnly = true, inProgressOnly = true),
                        {},
                        AVAILABLE_FEED_FILTER_CAPABILITIES,
                    )
                }
                onNodeWithText("Downloaded").assertIsSelected()
                onNodeWithText("In progress").assertIsSelected()
                onNodeWithText("Clear filters").assertExists()
            } finally {
                Locale.setDefault(previous)
            }
        }
}
