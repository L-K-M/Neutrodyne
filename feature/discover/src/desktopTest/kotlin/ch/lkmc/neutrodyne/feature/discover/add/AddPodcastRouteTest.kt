// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.discover.add

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.domain.AddResolution
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakeAddPodcastResolver
import ch.lkmc.neutrodyne.core.testing.FakeSubscribeUseCase
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.testing.testFeedPreview
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `AddPodcastKey` route's terminal effect (08 Add podcast sheet): a completed subscribe pops
 * the sheet and posts the "Subscribed" snackbar with an "Open" action on the single root
 * `SnackbarHostState`. `showSnackbar` serializes on the host's mutex and every screen's messages
 * share it, so an action-bearing snackbar must keep a bounded duration (08's rule: `Long` for
 * undo/action snackbars) — an ignored `Indefinite` one would starve all later messages.
 */
@OptIn(ExperimentalTestApi::class)
class AddPodcastRouteTest {
    private lateinit var previousLocale: Locale
    private val resolver = FakeAddPodcastResolver()
    private val subscribe = FakeSubscribeUseCase()
    private val viewModel = AddPodcastViewModel(resolver, subscribe)
    private val navigator = RecordingAppNavigator()
    private val host = SnackbarHostState()

    @BeforeTest
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        installFakeImageLoader()
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun subscribedSnackbarKeepsABoundedDuration() =
        runComposeUiTest {
            resolver.nextResolution = AddResolution.Feed(testFeedPreview(title = "A Show"))
            subscribe.nextOutcome = Outcome.Success(7L)
            setRoute()

            feedField().performTextInput("https://example.com/feed")
            onNodeWithText("Subscribe").performClick()
            waitForIdle()
            onNodeWithText("A Show").assertIsDisplayed()
            onNodeWithText("Subscribe").performClick()

            waitUntil(timeoutMillis = 5_000) { host.currentSnackbarData != null }
            assertEquals(
                listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.Pop),
                navigator.calls,
            )
            assertEquals(SnackbarDuration.Long, host.currentSnackbarData?.visuals?.duration)
        }

    private fun ComposeUiTest.setRoute() {
        setContent {
            // collectAsStateWithLifecycle needs a started owner; a bare scene provides none.
            CompositionLocalProvider(LocalLifecycleOwner provides ResumedLifecycleOwner()) {
                CompositionLocalProvider(
                    LocalPlatformKind provides PlatformKind.DESKTOP,
                    LocalUiClock provides TestClock(),
                    LocalAppNavigator provides navigator,
                    LocalSnackbarHost provides host,
                ) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        AddPodcastRoute(AddPodcastKey(input = null), viewModel)
                    }
                }
            }
        }
        waitForIdle()
    }

    /** `createUnsafe` skips the main-thread checks so the test can resume it off the UI thread. */
    private class ResumedLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)

        init {
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    /** The sheet's single address field — the first editable node. */
    private fun ComposeUiTest.feedField() = onAllNodes(hasSetTextAction()).onFirst()
}
