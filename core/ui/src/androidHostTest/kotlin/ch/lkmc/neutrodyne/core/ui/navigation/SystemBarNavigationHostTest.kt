// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import ch.lkmc.neutrodyne.core.designsystem.PlatformSystemBarAppearance
import ch.lkmc.neutrodyne.core.designsystem.StatusBarAppearance
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class SystemBarNavigationHostTest : NdNavHostHarness() {
    @Test
    fun predictivePreviewDoesNotTakeStatusOwnershipAndCancelRestoresHeader() {
        install()
        val controller = WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView)
        rule.runOnUiThread { navigation.push(PodcastKey(1)) }
        advanceFrames(24)
        assertFalse(controller.isAppearanceLightStatusBars)
        val input = DirectNavigationEventInput()
        rule.runOnUiThread {
            assertNotNull(dispatcher).addInput(input)
            input.backStarted(NavigationEvent(NavigationEvent.EDGE_LEFT, 0f, 16f, 400f))
            input.backProgressed(NavigationEvent(NavigationEvent.EDGE_LEFT, .45f, 300f, 400f))
        }
        advanceFrames(8)
        assertFalse(controller.isAppearanceLightStatusBars, "preview stole the current header's strip")
        rule.runOnUiThread { input.backCancelled() }
        advanceFrames(24)
        assertFalse(controller.isAppearanceLightStatusBars)
        assertTrue(controller.isAppearanceLightNavigationBars)
        rule.runOnUiThread { navigation.pop() }
        advanceFrames(24)
        assertTrue(controller.isAppearanceLightStatusBars)
    }

    @Test
    fun expandedPaneHeadersLeaveTheAppStripAlone() {
        install(partitions = 2)
        val controller = WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView)
        rule.runOnUiThread { navigation.push(PodcastKey(1)) }
        advanceFrames(24)
        assertTrue(controller.isAppearanceLightStatusBars)
        assertTrue(controller.isAppearanceLightNavigationBars)
    }

    private fun install(partitions: Int = 1) {
        buildFixedHost(
            installers =
                setOf<EntryProviderInstaller>({
                    entry<FeedsKey>(metadata = NdSceneMetadata.paneList()) {
                        HeaderSystemBars(Color.White)
                        Box(Modifier.fillMaxSize().background(Color.White))
                    }
                    entry<LibraryKey> {}
                    entry<UpNextKey> {}
                    entry<DownloadsKey> {}
                    entry<DiscoverKey> {}
                    entry<PodcastKey>(metadata = NdSceneMetadata.paneDetail()) {
                        HeaderSystemBars(Color.Black)
                        Box(Modifier.fillMaxSize().background(Color.Black))
                    }
                }),
            directive = PaneScaffoldDirective.Default.copy(maxHorizontalPartitions = partitions),
        )
        val host = assertNotNull(hostContent)
        rule.setContent {
            PlatformSystemBarAppearance(StatusBarAppearance.DarkIcons)
            host()
        }
        settle()
    }
}
