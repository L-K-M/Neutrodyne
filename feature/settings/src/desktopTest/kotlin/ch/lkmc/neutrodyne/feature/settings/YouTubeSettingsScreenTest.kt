// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilities
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class YouTubeSettingsScreenTest {
    @Test fun externalSettingsObserveCapabilitiesAndPersistPortablePreferences() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            runComposeUiTest {
                val settings = FakeSettingsRepository()
                val caps = FakeYouTubeCapabilitiesSource()
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
                    ) { YouTubePage(settings, caps, PlatformKind.DESKTOP) }
                }
                onNodeWithText("Suggest the podcast’s RSS feed").performClick()
                onNodeWithText("Mark played when opened on YouTube").performClick()
                waitForIdle()
                assertFalse(runBlocking { settings.get(YouTubeSettingKeys.SUGGEST_RSS) })
                assertFalse(runBlocking { settings.get(YouTubeSettingKeys.MARK_PLAYED_ON_OPEN) })
                onNodeWithText("Not yet available on this computer").assertExists()
                onNodeWithText("Play YouTube in the app").assertDoesNotExist()
                onNodeWithText("Check for engine update").assertDoesNotExist()
                runOnIdle {
                    caps.capabilities.value =
                        YouTubeCapabilities(
                            false,
                            false,
                            false,
                            false,
                            false,
                            ExternalReason.DISABLED_BY_USER,
                        )
                }
                onNodeWithText("In-app YouTube is off").assertExists()
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun engineControlsPersistAndBreakerRetryUsesHealth() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            runComposeUiTest {
                val settings = FakeSettingsRepository()
                val caps =
                    FakeYouTubeCapabilitiesSource().apply {
                        capabilities.value = YouTubeCapabilities(true, true, true, true, true, null)
                    }
                val engine =
                    ch.lkmc.neutrodyne.core.testing.FakeYouTubeEngine().apply {
                        status.value =
                            ch.lkmc.neutrodyne.youtube.api.EngineStatus(
                                ch.lkmc.neutrodyne.youtube.api.EngineAvailability.READY,
                                "2026.08.19",
                            )
                    }
                val health =
                    ch.lkmc.neutrodyne.core.testing.FakeYouTubeHealth().apply {
                        state.value =
                            ch.lkmc.neutrodyne.youtube.api.YouTubeHealthState(
                                breaker = ch.lkmc.neutrodyne.youtube.api.BreakerState.OPEN,
                                breakerOpenUntil = Long.MAX_VALUE,
                            )
                    }
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
                    ) { YouTubePage(settings, caps, PlatformKind.ANDROID, engine, health) }
                }
                onNodeWithText("Engine 2026.08.19 (bundled)").assertExists()
                onNodeWithText("YouTube engine ready").assertExists()
                onNodeWithText("Audio quality").performClick()
                onNodeWithText("Data saver").performClick()
                waitForIdle()
                kotlin.test.assertEquals(
                    ch.lkmc.neutrodyne.core.model.settings.YouTubeAudioQuality.DATA_SAVER,
                    runBlocking { settings.get(YouTubeSettingKeys.AUDIO_QUALITY) },
                )
                onNodeWithText("Try now").performClick()
                kotlin.test.assertEquals(1, health.retries)
                onNodeWithText("Play YouTube in the app").performClick()
                waitForIdle()
                assertFalse(runBlocking { settings.get(YouTubeSettingKeys.ENGINE_ENABLED) })
                onNodeWithText("Check for engine update").assertDoesNotExist()
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
