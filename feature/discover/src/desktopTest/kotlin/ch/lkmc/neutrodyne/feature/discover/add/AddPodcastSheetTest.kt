// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.discover.add

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.AddResolution
import ch.lkmc.neutrodyne.core.domain.SubscribeError
import ch.lkmc.neutrodyne.core.model.AlreadySubscribed
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedCandidate
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.testing.FakeAddPodcastResolver
import ch.lkmc.neutrodyne.core.testing.FakeSubscribeUseCase
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.testFeedPreview
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "Add a podcast" sheet driven end to end (08 Add podcast sheet): the real
 * [AddPodcastViewModel] on [FakeAddPodcastResolver]/[FakeSubscribeUseCase], with the UI doing
 * input → resolve → preview → subscribe, plus the invalid-URL, network and already-subscribed
 * paths. The ViewModel's `viewModelScope` lands on the Swing Main dispatcher (a desktopTest
 * dependency); the fakes answer synchronously so every step settles inside `waitForIdle`.
 */
@OptIn(ExperimentalTestApi::class)
class AddPodcastSheetTest {
    private lateinit var previousLocale: Locale
    private val resolver = FakeAddPodcastResolver()
    private val subscribe = FakeSubscribeUseCase()
    private val viewModel = AddPodcastViewModel(resolver, subscribe)
    private var openedPodcast: Long? = null
    private var dismissed = false

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
    fun inputResolvesToPreviewThenSubscribes() =
        runComposeUiTest {
            resolver.nextResolution = AddResolution.Feed(testFeedPreview(title = "A Show", author = "An Author"))
            subscribe.nextOutcome = Outcome.Success(7L)
            setSheet()

            feedField().performTextInput("https://example.com/feed")
            onNodeWithText("Subscribe").performClick()
            waitForIdle()

            // The preview card carries the title and "author · N episodes" line (08).
            onNodeWithText("A Show").assertIsDisplayed()
            assertEquals(listOf("https://example.com/feed"), resolver.inputs)

            onNodeWithText("Subscribe").performClick()
            waitForIdle()
            assertEquals(listOf("preview-1" to emptySet<Long>()), subscribe.subscribed)
            assertEquals(
                SubscribedPodcast(7L, "A Show", viewModel.uiState.value.operationGeneration),
                viewModel.uiState.value.done,
            )
        }

    @Test
    fun prefilledInputResolvesImmediately() =
        runComposeUiTest {
            // A deep link or a restored field resolves without typing (08, D24).
            resolver.nextResolution = AddResolution.Feed(testFeedPreview(title = "A Show"))
            setSheet(initialInput = "https://example.com/feed")
            waitForIdle()

            onNodeWithText("A Show").assertIsDisplayed()
            assertEquals(listOf("https://example.com/feed"), resolver.inputs)
        }

    @Test
    fun invalidUrlShowsInlineErrorAndRetries() =
        runComposeUiTest {
            resolver.nextResolution = AddResolution.Failure(AddPodcastError.InvalidUrl)
            setSheet()

            feedField().performTextInput("not a url at all")
            onNodeWithText("Subscribe").performClick()
            waitForIdle()
            onNodeWithText("That doesn't look like a feed address").assertIsDisplayed()

            // The failed state's Retry re-resolves the same input (08's Retry/Edit).
            resolver.nextResolution = AddResolution.Feed(testFeedPreview())
            onNodeWithText("Retry").performClick()
            waitForIdle()
            assertEquals(listOf("not a url at all", "not a url at all"), resolver.inputs)
            onNodeWithText("A Show").assertIsDisplayed()
        }

    @Test
    fun networkFailureShowsTheNetErrorText() =
        runComposeUiTest {
            resolver.nextResolution = AddResolution.Failure(AddPodcastError.Network(NetError.Offline))
            setSheet()

            feedField().performTextInput("https://example.com/feed")
            onNodeWithText("Subscribe").performClick()
            waitForIdle()
            onNodeWithText("You're offline").assertIsDisplayed()
        }

    @Test
    fun chooserPickReResolvesTheCandidate() =
        runComposeUiTest {
            resolver.nextResolution =
                AddResolution.Choose(
                    listOf(
                        FeedCandidate("https://a/feed.xml", "Feed A", 10, "html"),
                        FeedCandidate("https://b/feed.xml", "Feed B", 5, "html"),
                    ),
                )
            setSheet()

            feedField().performTextInput("https://example.com")
            onNodeWithText("Subscribe").performClick()
            waitForIdle()
            onNodeWithText("This page has several feeds").assertIsDisplayed()
            onNodeWithText("Feed A").assertIsDisplayed()

            resolver.nextResolution = AddResolution.Feed(testFeedPreview(title = "Picked Show"))
            onNodeWithText("Feed A").performClick()
            waitForIdle()
            assertEquals(listOf("https://example.com", "https://a/feed.xml"), resolver.inputs)
            onNodeWithText("Picked Show").assertIsDisplayed()
        }

    @Test
    fun authRequiredCollectsCredentials() =
        runComposeUiTest {
            resolver.nextResolution = AddResolution.Failure(AddPodcastError.AuthRequired(realm = null))
            setSheet()

            feedField().performTextInput("https://example.com/feed")
            onNodeWithText("Subscribe").performClick()
            waitForIdle()
            onNodeWithText("This feed needs a password").assertIsDisplayed()

            resolver.nextResolution = AddResolution.Feed(testFeedPreview())
            // Field order: the address field, then username, then password.
            onAllNodes(hasSetTextAction())[1].performTextInput("alice")
            onAllNodes(hasSetTextAction())[2].performTextInput("s3cret")
            onNodeWithText("Subscribe").performClick()
            waitForIdle()

            assertEquals(
                listOf("https://example.com/feed" to BasicCredentials("alice", "s3cret")),
                resolver.credentialInputs,
            )
            onNodeWithText("A Show").assertIsDisplayed()
        }

    @Test
    fun alreadySubscribedPreviewOffersOpen() =
        runComposeUiTest {
            resolver.nextResolution =
                AddResolution.Feed(
                    testFeedPreview(alreadySubscribed = AlreadySubscribed(42L, exact = true)),
                )
            setSheet()

            feedField().performTextInput("https://example.com/feed")
            onNodeWithText("Subscribe").performClick()
            waitForIdle()

            onNodeWithText("Already subscribed").assertIsDisplayed()
            assertTrue(onAllNodes(hasText("Subscribe")).fetchSemanticsNodes().isEmpty())
            onNodeWithText("Open").performClick()
            assertEquals(42L, openedPodcast)
        }

    @Test
    fun subscribeDuplicateSurfacesAlreadySubscribed() =
        runComposeUiTest {
            // The race-time dedupe (08): subscribe's AlreadySubscribed swaps Subscribe for Open.
            resolver.nextResolution = AddResolution.Feed(testFeedPreview())
            subscribe.nextOutcome = Outcome.Failure(SubscribeError.AlreadySubscribed(42L))
            setSheet()

            feedField().performTextInput("https://example.com/feed")
            onNodeWithText("Subscribe").performClick()
            waitForIdle()
            onNodeWithText("Subscribe").performClick()
            waitForIdle()

            onNodeWithText("Already subscribed").assertIsDisplayed()
            onNodeWithText("Open").performClick()
            assertEquals(42L, openedPodcast)
            assertNull(viewModel.uiState.value.done)
        }

    @Test
    fun subscribeFailureShowsInlineError() =
        runComposeUiTest {
            resolver.nextResolution = AddResolution.Feed(testFeedPreview())
            subscribe.nextOutcome = Outcome.Failure(SubscribeError.Storage)
            setSheet()

            feedField().performTextInput("https://example.com/feed")
            onNodeWithText("Subscribe").performClick()
            waitForIdle()
            onNodeWithText("Subscribe").performClick()
            waitForIdle()

            onNodeWithText("Not enough storage space").assertIsDisplayed()
            assertNull(viewModel.uiState.value.done)
        }

    @Test
    fun cancelDismissesTheSheet() =
        runComposeUiTest {
            setSheet()
            onNodeWithText("Cancel").performClick()
            assertTrue(dismissed)
        }

    private fun ComposeUiTest.setSheet(initialInput: String? = null) {
        setContent {
            // collectAsStateWithLifecycle (rule 10) needs a started owner; a bare
            // compose scene provides none, so the test installs a RESUMED one.
            CompositionLocalProvider(LocalLifecycleOwner provides ResumedLifecycleOwner()) {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                CompositionLocalProvider(
                    LocalPlatformKind provides PlatformKind.DESKTOP,
                    LocalUiClock provides TestClock(),
                ) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        AddPodcastSheet(
                            initialInput = initialInput,
                            state = state,
                            onResolve = viewModel::resolve,
                            onCredentials = viewModel::resolveWithCredentials,
                            onCancelResolve = viewModel::cancelResolve,
                            onInputChange = viewModel::onInputChanged,
                            onCandidate = viewModel::resolve,
                            onSubscribe = viewModel::subscribe,
                            onOpenPodcast = { openedPodcast = it },
                            onDismiss = { dismissed = true },
                        )
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
