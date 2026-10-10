// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.pressKey
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S5's Android legs (01 S5): the shared `NeutrodyneRoot`/`NeutrodyneNavHost` under test-only
 * entries in [SpikeRootActivity]. Covers per-tab `rememberSaveable` state across tab switches, the
 * process-death restore of both tabs' back stacks via `ActivityScenario.recreate()` (the
 * `StateRestorationTester` round trip of `rememberNavigationState` lives in
 * [NavigationStateRestorationTest]), the entry-scoped ViewModel
 * the host's `rememberViewModelStoreNavEntryDecorator()` provides, the sheet/dialog overlay scenes
 * dismissed by system back, and the 08 back order (`pop()` at a non-Feeds root selects Feeds).
 * Runs on the API 26 and 36 GMDs in CI; no emulator exists locally.
 *
 * Debug-only (`androidTestDebug`): its host [SpikeRootActivity] lives in the debug source set, so
 * the release-variant test APK (`-PtestBuildType=release`, the release smoke runs) must not
 * compile it.
 */
@RunWith(AndroidJUnit4::class)
class Nav3SpikeTest {
    @get:Rule
    val compose = createAndroidComposeRule<SpikeRootActivity>()

    @Before
    fun resetProbe() = PodcastVmProbe.reset()

    @Test
    fun perTabStateSurvivesTabSwitch() {
        compose.onNodeWithText("feeds-inc").performClick()
        compose.onNodeWithText("feeds-count:1").assertIsDisplayed()

        // Library's entries leave composition entirely; rememberSaveable must restore the count.
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithText("library-inc").performClick()
        compose.onNodeWithText("library-count:1").assertIsDisplayed()

        compose.onNodeWithTag("nav_feeds").performClick()
        compose.onNodeWithText("feeds-count:1").assertIsDisplayed()
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithText("library-count:1").assertIsDisplayed()
    }

    @Test
    fun backStacksAndSelectedTabSurviveRecreation() {
        compose.onNodeWithText("feeds-inc").performClick()
        compose.onNodeWithText("open-podcast").performClick()
        compose.onNodeWithText("podcast-3").assertIsDisplayed()

        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithText("library-inc").performClick()
        compose.onNodeWithText("open-podcast").performClick()
        compose.onNodeWithText("podcast-9").assertIsDisplayed()

        // The process-death leg: onCreate re-composes the root against the saved state.
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        // Library stayed selected, its pushed detail and its root's saveable state restored.
        compose.onNodeWithText("podcast-9").assertIsDisplayed()
        pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("library-count:1").assertIsDisplayed()

        // Feeds' stack restored too: the pushed detail is on top, the root's count below it.
        compose.onNodeWithTag("nav_feeds").performClick()
        compose.onNodeWithText("podcast-3").assertIsDisplayed()
        pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("feeds-count:1").assertIsDisplayed()
    }

    @Test
    fun viewModelsAreScopedPerEntryAndSurviveHiddenTabs() {
        compose.onNodeWithText("open-podcast").performClick()
        compose.onNodeWithText("podcast-3").assertIsDisplayed()

        compose.onNodeWithText("open-next-podcast").performClick()
        compose.onNodeWithText("podcast-5").assertIsDisplayed()

        // Two distinct PodcastKey entries hold two distinct ViewModels.
        val key3 = PodcastKey(FEEDS_PODCAST)
        val key5 = PodcastKey(FEEDS_PODCAST_2)
        val vm3 = PodcastVmProbe.instancesOf(key3).single()
        val vm5 = PodcastVmProbe.instancesOf(key5).single()
        assertThat(vm3).isNotSameInstanceAs(vm5)

        // While the Library tab shows, Feeds' entries keep their ViewModel stores (the decorator
        // clears on pop, not on hide).
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithText("library-content").assertIsDisplayed()
        compose.onNodeWithTag("nav_feeds").performClick()

        compose.onNodeWithText("podcast-5").assertIsDisplayed()
        assertThat(PodcastVmProbe.instancesOf(key5)).containsExactly(vm5)
        assertThat(PodcastVmProbe.instancesOf(key3)).containsExactly(vm3)

        pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("podcast-3").assertIsDisplayed()
        assertThat(PodcastVmProbe.instancesOf(key3)).containsExactly(vm3)
    }

    @Test
    fun sheetRendersAboveContentAndBackDismissesIt() {
        compose.onNodeWithText("open-sheet").performClick()
        compose.onNodeWithText("add-podcast-sheet").assertIsDisplayed()
        compose.onNodeWithText("feeds-content").assertIsDisplayed()

        pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("add-podcast-sheet").assertDoesNotExist()
        compose.onNodeWithText("feeds-content").assertIsDisplayed()
    }

    @Test
    fun dialogRendersAboveContentAndBackDismissesIt() {
        compose.onNodeWithText("open-dialog").performClick()
        compose.onNodeWithText("export-dialog").assertIsDisplayed()
        compose.onNodeWithText("feeds-content").assertIsDisplayed()

        // A modal dialog owns focus; Espresso's default root can retain the unfocused activity
        // after the underlying-content assertion. Select the dialog and inject real system back.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.runOnIdle { !compose.activity.hasWindowFocus() }
        }
        onView(isRoot()).inRoot(isDialog()).perform(pressKey(KeyEvent.KEYCODE_BACK))
        compose.waitForIdle()
        compose.onNodeWithText("export-dialog").assertDoesNotExist()
        compose.onNodeWithText("feeds-content").assertIsDisplayed()
    }

    @Test
    fun backAtANonFeedsRootSelectsFeeds() {
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithText("library-content").assertIsDisplayed()

        pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("feeds-content").assertIsDisplayed()
    }
}
