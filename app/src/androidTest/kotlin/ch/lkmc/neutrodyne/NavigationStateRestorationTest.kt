// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.ui.navigation.NavigationState
import ch.lkmc.neutrodyne.core.ui.navigation.rememberNavigationState
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S5's saved-state leg (01 S5): both tabs' back stacks survive a saved-instance-state round trip
 * through `NavKeySerializers.savedStateConfiguration`. `StateRestorationTester` sets the content
 * itself, so it needs a bare compose rule (the ui-test-manifest `ComponentActivity`), not
 * `SpikeRootActivity`, which sets its own content in `onCreate`.
 */
@RunWith(AndroidJUnit4::class)
class NavigationStateRestorationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun navigationStateRestoresEveryDeclaredKey() {
        val restoration = StateRestorationTester(compose)
        var state: NavigationState? = null
        restoration.setContent {
            state = rememberNavigationState()
        }

        compose.runOnIdle {
            val nav = state!!
            nav.selectTab(LibraryKey)
            nav.push(PodcastKey(9))
            nav.push(AddPodcastKey("https://feed.example"))
            nav.selectTab(FeedsKey)
            nav.push(PodcastKey(3))
        }

        // The saved-state round trip serializes both stacks through
        // NavKeySerializers.savedStateConfiguration, not just the selected tab's.
        restoration.emulateSavedInstanceStateRestore()

        compose.runOnIdle {
            val nav = state!!
            assertThat(nav.selectedTab).isEqualTo(FeedsKey)
            assertThat(nav.stack(FeedsKey).toList())
                .containsExactly(FeedsKey, PodcastKey(3L))
                .inOrder()
            assertThat(nav.stack(LibraryKey).toList())
                .containsExactly(LibraryKey, PodcastKey(9L), AddPodcastKey("https://feed.example"))
                .inOrder()
        }
    }
}
