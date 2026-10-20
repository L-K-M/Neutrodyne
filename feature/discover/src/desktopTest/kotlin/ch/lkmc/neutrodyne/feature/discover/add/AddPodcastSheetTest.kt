// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.discover.add

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
import ch.lkmc.neutrodyne.youtube.api.ChannelResolution
import ch.lkmc.neutrodyne.youtube.api.ResolvedVia
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    private val groups =
        ch.lkmc.neutrodyne.core.testing
            .FakeGroupRepository()
    private val subscribe = FakeSubscribeUseCase()
    private val viewModel by lazy {
        AddPodcastViewModel(
            resolver,
            subscribe,
            groups,
            ch.lkmc.neutrodyne.core.testing
                .FakeYouTubeChannelResolver(),
            ch.lkmc.neutrodyne.core.testing
                .FakeYouTubeChannelRepository(),
            ch.lkmc.neutrodyne.core.testing
                .FakeYouTubeCapabilitiesSource(),
        )
    }
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

    @Test fun channelPreviewShowsMetadataVariantsAndOpenWithoutEpisodeCount() =
        runComposeUiTest {
            val resolved =
                ChannelResolution.Resolved(
                    "UCBR8-60-B28hp2BmDPdntcQ",
                    "Channel title",
                    null,
                    null,
                    null,
                    ResolvedVia.STATIC,
                )
            setSheet(
                initialInput = "@channel",
                onResolve = {},
                stateOverride =
                    AddPodcastUiState(step = AddSheetStep.Channel(resolved, 1, alreadySubscribedId = 44)),
            )
            onNodeWithText("Channel title").assertIsDisplayed()
            onNodeWithText("Long-form uploads only; change in podcast settings").assertIsDisplayed()
            onNodeWithText("Already subscribed").assertIsDisplayed()
            onNodeWithText("Open").performClick()
            assertEquals(44, openedPodcast)
            onNodeWithText("0 episodes").assertDoesNotExist()
        }

    @Test fun youTubeAffordanceNeedsAQueryAndTheCapabilityAndCallsItsOwnCallback() =
        runComposeUiTest {
            var directory: String? = null
            var youtube: String? = null
            // Capability off: only the ordinary directory destination renders (W10 D1).
            setSheet(
                stateOverride = AddPodcastUiState(step = AddSheetStep.Input(searchQuery = "history")),
                onSearch = { directory = it },
                onYouTubeSearch = { youtube = it },
            )
            onNodeWithText("Search for ‘history’").assertIsDisplayed()
            onNodeWithText("Search YouTube for ‘history’").assertDoesNotExist()
            // Capability on: the second destination appears and reaches only its own callback.
            setSheet(
                stateOverride =
                    AddPodcastUiState(step = AddSheetStep.Input(searchQuery = "history"), channelSearch = true),
                onSearch = { directory = it },
                onYouTubeSearch = { youtube = it },
            )
            onNodeWithText("Search YouTube for ‘history’").performClick()
            assertEquals("history", youtube)
            assertNull(directory)
            onNodeWithText("Search for ‘history’").performClick()
            assertEquals("history", directory)
            // No search query (plain input error): neither destination renders.
            setSheet(stateOverride = AddPodcastUiState(step = AddSheetStep.Input(), channelSearch = true))
            onNodeWithText("Search for ‘history’").assertDoesNotExist()
            onNodeWithText("Search YouTube for ‘history’").assertDoesNotExist()
        }

    @Test fun playlistFailureOffersOwnerWithoutSubscribe() =
        runComposeUiTest {
            val failure = ChannelResolution.PlaylistUnsupported("PL1234567890123", "UCBR8-60-B28hp2BmDPdntcQ", "Owner")
            val state = AddPodcastUiState(step = AddSheetStep.ChannelFailure(failure))
            setSheet(initialInput = "playlist", onResolve = {}, stateOverride = state)
            onNodeWithText("YouTube playlists can’t be subscribed to yet.").assertIsDisplayed()
            onNodeWithText("Subscribe to Owner").assertIsDisplayed()
            onNodeWithText("Subscribe", substring = false).assertIsNotEnabled()
        }

    @Test
    fun newGroupCanBeSubscribedBeforeGroupObserverCatchesUp() =
        runComposeUiTest {
            var chosen: Set<Long>? = null
            setSheet(
                initialInput = "https://example.com/feed",
                stateOverride =
                    AddPodcastUiState(
                        step = AddSheetStep.Preview(testFeedPreview()),
                        creatingGroup = true,
                    ),
            )
            onNodeWithText("Subscribe").assertIsNotEnabled()
            setSheet(
                initialInput = "https://example.com/feed",
                stateOverride =
                    AddPodcastUiState(
                        step = AddSheetStep.Preview(testFeedPreview()),
                        createdGroupId = 9L,
                    ),
                onSubscribe = { chosen = it },
            )
            onNodeWithText("Subscribe").performClick()
            assertEquals(setOf(9L), chosen)
        }

    @Test
    fun chosenGroupsSurviveRecreationAndNewGroupIsSelected() =
        runComposeUiTest {
            resolver.nextResolution = AddResolution.Feed(testFeedPreview())
            var registry = SaveableStateRegistry(null) { true }
            setSheet(initialInput = "https://example.com/feed", registry = registry)
            onNodeWithText("+ New group").performClick()
            onAllNodes(hasSetTextAction())[1].performTextInput("Tech")
            onNodeWithText("Create group").performClick()
            waitUntil { groups.groups.value.isNotEmpty() }
            waitForIdle()
            onNodeWithText("Tech").assertIsSelected()
            onNodeWithText("Tech").performClick()
            runOnIdle { registry = SaveableStateRegistry(registry.performSave()) { true } }
            setSheet(registry = registry)
            onNodeWithText("Tech").assertIsNotSelected()
            onNodeWithText("Tech").performClick()
            runOnIdle { registry = SaveableStateRegistry(registry.performSave()) { true } }
            setSheet(registry = registry)
            onNodeWithText("Tech").assertIsSelected()
            onNodeWithText("Subscribe").performClick()
            waitUntil { subscribe.subscribed.isNotEmpty() }
            assertEquals(
                setOf(
                    groups.groups.value
                        .single()
                        .id,
                ),
                subscribe.subscribed.single().second,
            )
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

    /**
     * 95-3: while `Choose` lists candidates the primary action is inert — only a candidate row
     * advances (08). The button reads disabled and Enter fires no resolve; the spy counts
     * submissions the ViewModel's dedupe would otherwise hide.
     */
    @Test
    fun choosingDisablesSubscribeAndEnter() =
        runComposeUiTest {
            resolver.nextResolution =
                AddResolution.Choose(
                    listOf(
                        FeedCandidate("https://a/feed.xml", "Feed A", 10, "html"),
                        FeedCandidate("https://b/feed.xml", "Feed B", 5, "html"),
                    ),
                )
            val resolves = mutableListOf<String>()
            setSheet(onResolve = {
                resolves += it
                viewModel.resolve(it)
            })

            feedField().performTextInput("https://example.com")
            onNodeWithText("Subscribe").performClick()
            waitForIdle()
            onNodeWithText("This page has several feeds").assertIsDisplayed()

            onNodeWithText("Subscribe").assertIsNotEnabled()
            feedField().performImeAction()
            waitForIdle()
            assertEquals(listOf("https://example.com"), resolves)
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

    /**
     * 95-4: while the subscribe transaction runs the preview card shows the "Subscribing…"
     * indicator (08's wording) and the primary action is disabled — a tap used to no-op
     * through the ViewModel's guard while the button still read enabled.
     */
    @Test
    fun subscribingShowsProgressAndDisablesSubscribe() =
        runComposeUiTest {
            resolver.nextResolution = AddResolution.Feed(testFeedPreview())
            val gate = CompletableDeferred<Unit>()
            subscribe.gate = gate
            setSheet()

            feedField().performTextInput("https://example.com/feed")
            onNodeWithText("Subscribe").performClick()
            waitForIdle()
            onNodeWithText("Subscribe").performClick()
            waitForIdle()

            onNodeWithText("Subscribing…").assertIsDisplayed()
            onNodeWithText("Subscribe").assertIsNotEnabled()

            // Success lands in `done` and the route pops the sheet over it — the step's
            // flag is not cleared for a composition that is already leaving. The gated
            // coroutine resumes on the Swing Main queue waitUntil pumps.
            gate.complete(Unit)
            waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.done != null }
            assertEquals(
                SubscribedPodcast(1L, "A Show", viewModel.uiState.value.operationGeneration),
                viewModel.uiState.value.done,
            )
        }

    @Test
    fun editingTheAddressKeepsTheSheetOnB() =
        runComposeUiTest {
            // Review round 3's interval: subscribe A in flight, the field edited to B
            // without submitting, then A's late success — the sheet must stay open on B.
            resolver.nextResolution = AddResolution.Feed(testFeedPreview(title = "A Show"))
            subscribe.nextOutcome = Outcome.Success(7L)
            val gate = CompletableDeferred<Unit>()
            subscribe.gate = gate
            setSheet()

            feedField().performTextInput("https://a.example.com/feed")
            onNodeWithText("Subscribe").performClick()
            waitForIdle()
            onNodeWithText("Subscribe").performClick()
            waitForIdle()

            feedField().performTextReplacement("https://b.example.com/feed")
            waitForIdle()
            gate.complete(Unit)
            // waitForIdle can return before the EDT runs A's posted resumption; runOnIdle's
            // invokeAndWait drains it, so a regressed VM would write `done` before the assert.
            runOnIdle {}

            assertNull(viewModel.uiState.value.done)
            val field = feedField().fetchSemanticsNode()
            assertEquals(
                "https://b.example.com/feed",
                field.config[SemanticsProperties.EditableText].text,
            )
        }

    /**
     * 95-1: a save/restore round trip (process death) brings the address field back and
     * re-resolves to the auth prompt, but the typed credentials do not return with it — 08's
     * saveable set is only the input, the preview id and the chosen groups, so the password
     * must never land in the saved-state bundle.
     */
    @Test
    fun credentialsDoNotSurviveRecreation() =
        runComposeUiTest {
            resolver.nextResolution =
                AddResolution.Failure(AddPodcastError.AuthRequired(realm = null))
            var restored: Map<String, List<Any?>> = emptyMap()
            var registry = SaveableStateRegistry(restored) { true }
            setSheet(registry = registry)

            feedField().performTextInput("https://example.com/feed")
            onNodeWithText("Subscribe").performClick()
            waitForIdle()
            onNodeWithText("This feed needs a password").assertIsDisplayed()
            // Field order: the address field, then username, then password.
            onAllNodes(hasSetTextAction())[1].performTextInput("alice")
            onAllNodes(hasSetTextAction())[2].performTextInput("s3cret")

            runOnIdle { restored = registry.performSave() }
            registry = SaveableStateRegistry(restored) { true }
            setSheet(registry = registry)

            // The restored field re-resolves to the same auth step (the fake still answers
            // AuthRequired); the re-shown credential fields start empty.
            onNodeWithText("This feed needs a password").assertIsDisplayed()
            assertEquals("https://example.com/feed", editableText(0))
            assertEquals("", editableText(1))
            assertEquals("", editableText(2))
        }

    /**
     * 95-2: while the lookup runs only its own Cancel renders — the row's dismiss Cancel would
     * carry the same label for a different effect (the wireframe shows one). Cancelling returns
     * to the editable field; it does not dismiss the sheet.
     */
    @Test
    fun resolvingShowsOnlyTheLookupCancel() =
        runComposeUiTest {
            resolver.gate = CompletableDeferred()
            setSheet()

            feedField().performTextInput("https://example.com/feed")
            onNodeWithText("Subscribe").performClick()
            waitForIdle()

            onNodeWithText("Looking up…").assertIsDisplayed()
            onAllNodes(hasText("Cancel")).assertCountEquals(1)

            onNodeWithText("Cancel").performClick()
            waitForIdle()
            onNodeWithText("Looking up…").assertDoesNotExist()
            assertFalse(dismissed)
        }

    @Test
    fun cancelDismissesTheSheet() =
        runComposeUiTest {
            setSheet()
            onNodeWithText("Cancel").performClick()
            assertTrue(dismissed)
        }

    private fun ComposeUiTest.setSheet(
        initialInput: String? = null,
        registry: SaveableStateRegistry = SaveableStateRegistry(null) { true },
        onResolve: (String) -> Unit = viewModel::resolve,
        stateOverride: AddPodcastUiState? = null,
        onSubscribe: (Set<Long>) -> Unit = viewModel::subscribe,
        onSearch: (String) -> Unit = {},
        onYouTubeSearch: (String) -> Unit = {},
    ) {
        setContent {
            // collectAsStateWithLifecycle (rule 10) needs a started owner; a bare
            // compose scene provides none, so the test installs a RESUMED one.
            CompositionLocalProvider(LocalLifecycleOwner provides ResumedLifecycleOwner()) {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                CompositionLocalProvider(
                    LocalPlatformKind provides PlatformKind.DESKTOP,
                    LocalUiClock provides TestClock(),
                    LocalSaveableStateRegistry provides registry,
                ) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        AddPodcastSheet(
                            initialInput = initialInput,
                            state = stateOverride ?: state,
                            onResolve = onResolve,
                            onCredentials = viewModel::resolveWithCredentials,
                            onCancelResolve = viewModel::cancelResolve,
                            onSearch = onSearch,
                            onYouTubeSearch = onYouTubeSearch,
                            onInputChange = viewModel::onInputChanged,
                            onCandidate = viewModel::resolve,
                            onSubscribe = onSubscribe,
                            onCreateGroup = viewModel::createGroup,
                            onConsumeCreatedGroup = viewModel::consumeCreatedGroup,
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

    /** The [index]-th editable node's current text (0 is the address field). */
    private fun ComposeUiTest.editableText(index: Int): String =
        onAllNodes(hasSetTextAction())[index]
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text
}
