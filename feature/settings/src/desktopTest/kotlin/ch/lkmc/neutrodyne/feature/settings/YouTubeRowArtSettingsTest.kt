// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.YouTubeRowArt
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class YouTubeRowArtSettingsTest {
    private lateinit var previous: Locale

    @BeforeTest fun english() {
        previous = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest fun restore() {
        Locale.setDefault(previous)
    }

    @Test fun chooserTracksChannelsAndPersistsChoice() =
        runComposeUiTest {
            val settings = FakeSettingsRepository()
            val channels = MutableStateFlow(false)
            val owner =
                object : LifecycleOwner {
                    override val lifecycle =
                        LifecycleRegistry.createUnsafe(this).apply {
                            handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                        }
                }
            setContent {
                CompositionLocalProvider(
                    LocalLifecycleOwner provides owner,
                    LocalAppNavigator provides RecordingAppNavigator(),
                    LocalSnackbarHost provides SnackbarHostState(),
                ) {
                    AppearancePage(settings, DynamicColorSupport.UNAVAILABLE, PlatformKind.DESKTOP, channels)
                }
            }
            onNodeWithText("YouTube row artwork").assertDoesNotExist()
            runOnIdle { channels.value = true }
            onNodeWithText("YouTube row artwork").performScrollTo().performClick()
            onAllNodesWithText("Video thumbnail").onLast().assertIsSelected()
            onNodeWithText("Channel avatar").performClick()
            waitForIdle()
            assertEquals(YouTubeRowArt.CHANNEL_AVATAR, saved(settings))
            onNodeWithText("YouTube row artwork").performScrollTo().performClick()
            onAllNodesWithText("Channel avatar").onLast().assertIsSelected()
            runOnIdle { channels.value = false }
            onNodeWithText("YouTube row artwork").assertDoesNotExist()
            onNodeWithText("Video thumbnail").assertDoesNotExist()
            assertEquals(YouTubeRowArt.CHANNEL_AVATAR, saved(settings))
        }

    private fun saved(settings: FakeSettingsRepository): YouTubeRowArt =
        runBlocking { settings.get(AppearanceSettingKeys.YOUTUBE_ROW_ART) }
}
