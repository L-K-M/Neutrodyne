// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.paging.PagingData
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.FeedSourceRef
import ch.lkmc.neutrodyne.core.model.FeedTab
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Android's tester exercises Bundle encoding; desktop's platformEncodeDecode is unimplemented. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedsRestorationHostTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun stringPagerKeysRestoreSelectedGroupAndScroll() {
        val ref = FeedSourceRef.Group("00000000-0000-4000-8000-000000000001")
        val state =
            FeedsUiState(
                tabs =
                    persistentListOf(
                        FeedTabUi(FeedTab(FeedSource.All, "All", null, null, null), FeedSourceRef.All),
                        FeedTabUi(FeedTab(FeedSource.Group(1), "Tech", null, null, ref.uuid), ref),
                    ),
                selected = ref,
            )
        val rows = (1L..100L).map { FeedItem.Episode(testEpisodeRow(it, title = "Episode $it")) }
        val feed = flowOf(PagingData.from<FeedItem>(rows))
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(
                LocalAppNavigator provides RecordingAppNavigator(),
                LocalUiClock provides TestClock(),
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    FeedsScreen(
                        state = state,
                        pagingFor = { feed },
                        onRefresh = {},
                        onFiltersChange = { _, _ -> },
                        onAction = {},
                        onMarkAllPlayedClick = {},
                        onAddPodcast = {},
                        onSearch = {},
                    )
                }
            }
        }
        val list = compose.onNode(hasTestTag("feed-list") and hasAnyAncestor(hasTestTag("feed-page:${ref.encode()}")))
        list.performScrollToIndex(35)
        val before = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(before > 0)

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("feed-tab:${ref.encode()}").assertIsSelected()
        list.assertIsDisplayed()
        assertEquals(before, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value())
    }
}
