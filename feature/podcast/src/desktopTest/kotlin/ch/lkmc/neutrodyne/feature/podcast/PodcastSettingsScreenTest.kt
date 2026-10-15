// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.testing.testFeedInfo
import ch.lkmc.neutrodyne.core.testing.testPodcastDetail
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.FileSaver
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.feed_err_not_a_feed
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Podcast settings through `runComposeUiTest` (08 Podcast settings): the General and Feed rows,
 * the custom-title / episode-order / edit-address / credentials dialogs, and the private feed's
 * reveal warning. Writes are asserted as the callbacks the route forwards to the ViewModel.
 */
@OptIn(ExperimentalTestApi::class)
class PodcastSettingsScreenTest {
    private lateinit var previousLocale: Locale
    private val navigator = RecordingAppNavigator()

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
    fun loadingShowsSpinner() =
        runComposeUiTest {
            setSettings(PodcastSettingsUiState())
            onNodeWithText("Loading podcast…").assertIsDisplayed()
        }

    @Test
    fun showsGeneralAndFeedSections() =
        runComposeUiTest {
            setSettings(
                PodcastSettingsUiState(
                    detail = testPodcastDetail(7, displayTitle = "The Show"),
                    feedInfo =
                        testFeedInfo(
                            feedUrl = "https://feeds.example.com/show.xml",
                            redactedUrl = "https://feeds.…/show.xml",
                        ),
                    loaded = true,
                ),
            )
            onNodeWithText("Podcast settings · The Show").assertIsDisplayed()
            onNodeWithText("Custom title").assertIsDisplayed()
            onNodeWithText("Show in All").assertIsDisplayed()
            onNodeWithText("Episode order").assertIsDisplayed()
            onNodeWithText("Feed address").assertIsDisplayed()
            // The address stays redacted until tapped.
            onNodeWithText("https://feeds.…/show.xml · Tap to show").assertIsDisplayed()
            onNodeWithText("https://feeds.example.com/show.xml").assertDoesNotExist()
        }

    @Test
    fun feedAddressRevealsOnTap() =
        runComposeUiTest {
            setSettings(
                PodcastSettingsUiState(
                    detail = testPodcastDetail(7),
                    feedInfo =
                        testFeedInfo(
                            feedUrl = "https://feeds.example.com/show.xml",
                            redactedUrl = "https://feeds.…/show.xml",
                        ),
                    loaded = true,
                ),
            )
            onNodeWithText("Feed address").performClick()
            onNodeWithText("https://feeds.example.com/show.xml").assertIsDisplayed()
        }

    @Test
    fun privateFeedAddressWarnsBeforeReveal() =
        runComposeUiTest {
            setSettings(
                PodcastSettingsUiState(
                    detail = testPodcastDetail(7),
                    feedInfo =
                        testFeedInfo(
                            feedUrl = "https://secret.example.com/feed.xml",
                            redactedUrl = "https://secret.…/feed.xml",
                            isPrivate = true,
                        ),
                    loaded = true,
                ),
            )
            // The full URL stays out of the tree before consent, while the warning is
            // open, and after cancelling it — only confirming reveals it.
            onNodeWithText("https://secret.example.com/feed.xml").assertDoesNotExist()
            onNodeWithText("Feed address").performClick()
            onNodeWithText("https://secret.example.com/feed.xml").assertDoesNotExist()
            onAllNodes(hasClickAction() and hasText("Cancel")).onFirst().performClick()
            onNodeWithText("https://secret.example.com/feed.xml").assertDoesNotExist()

            onNodeWithText("Feed address").performClick()
            onAllNodes(hasClickAction() and hasText("Tap to show")).onFirst().performClick()
            onNodeWithText("https://secret.example.com/feed.xml").assertIsDisplayed()
        }

    @Test
    fun customTitleDialogWrites() =
        runComposeUiTest {
            var title: String? = "unset"
            setSettings(
                PodcastSettingsUiState(detail = testPodcastDetail(7), loaded = true),
                onCustomTitle = { title = it },
            )
            onNodeWithText("Custom title").performClick()
            onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("Renamed show")
            onAllNodes(hasClickAction() and hasText("Save")).onFirst().performClick()
            assertEquals("Renamed show", title)
        }

    @Test
    fun blankCustomTitleClears() =
        runComposeUiTest {
            var title: String? = "unset"
            setSettings(
                PodcastSettingsUiState(detail = testPodcastDetail(7), loaded = true),
                onCustomTitle = { title = it },
            )
            onNodeWithText("Custom title").performClick()
            onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("   ")
            onAllNodes(hasClickAction() and hasText("Save")).onFirst().performClick()
            assertEquals(null, title)
        }

    @Test
    fun showInAllSwitchToggles() =
        runComposeUiTest {
            var include: Boolean? = null
            setSettings(
                PodcastSettingsUiState(
                    detail = testPodcastDetail(7, includeInAll = true),
                    loaded = true,
                ),
                onIncludeInAll = { include = it },
            )
            // 08's General section: the whole row is the toggle target.
            onNodeWithText("Show in All").assertIsDisplayed().performClick()
            assertEquals(false, include)
        }

    @Test
    fun showInAllSwitchReflectsTheReadModel() =
        runComposeUiTest {
            var include: Boolean? = null
            setSettings(
                PodcastSettingsUiState(
                    detail = testPodcastDetail(7, includeInAll = false),
                    loaded = true,
                ),
                onIncludeInAll = { include = it },
            )
            onNodeWithText("Show in All").performClick()
            assertEquals(true, include)
        }

    @Test
    fun orderDialogWrites() =
        runComposeUiTest {
            var order: FeedOrder? = null
            setSettings(
                PodcastSettingsUiState(detail = testPodcastDetail(7), loaded = true),
                onOrderChange = { order = it },
            )
            onNodeWithText("Episode order").performClick()
            onNodeWithText("Oldest first").performClick()
            assertEquals(FeedOrder.OLDEST_FIRST, order)
        }

    @Test
    fun feedAccountControlsDispatchEditAndCredentials() =
        runComposeUiTest {
            var edited: String? = null
            var credentials: BasicCredentials? = null
            setSettings(
                PodcastSettingsUiState(
                    detail = testPodcastDetail(7),
                    feedInfo = testFeedInfo(feedUrl = "https://feeds.example.com/show.xml"),
                    loaded = true,
                ),
                onEditFeedUrl = {
                    edited = it
                    null
                },
                onCredentials = { credentials = it },
            )
            // M1b restores both Feed-section rows (08's resolved deviation).
            onNodeWithText("Edit feed address").performClick()
            onAllNodes(hasSetTextAction())
                .onFirst()
                .performTextReplacement("https://other.example.com/feed")
            onAllNodes(hasClickAction() and hasText("Save")).onFirst().performClick()
            waitForIdle()
            assertEquals("https://other.example.com/feed", edited)
            // A null return committed the edit — the dialog closes.
            onNodeWithText("New feed address").assertDoesNotExist()

            onNodeWithText("Username and password").performClick()
            onAllNodes(hasSetTextAction())[0].performTextInput("alice")
            onAllNodes(hasSetTextAction())[1].performTextInput("hunter2")
            onAllNodes(hasClickAction() and hasText("Save")).onFirst().performClick()
            assertEquals(BasicCredentials("alice", "hunter2"), credentials)
        }

    @Test
    fun editUrlFailureShowsInlineErrorAndKeepsTheDialog() =
        runComposeUiTest {
            setSettings(
                PodcastSettingsUiState(
                    detail = testPodcastDetail(7),
                    feedInfo = testFeedInfo(feedUrl = "https://feeds.example.com/show.xml"),
                    loaded = true,
                ),
                onEditFeedUrl = { UiText.Res(Res.string.feed_err_not_a_feed) },
            )
            onNodeWithText("Edit feed address").performClick()
            onAllNodes(hasSetTextAction())
                .onFirst()
                .performTextReplacement("https://other.example.com/page")
            onAllNodes(hasClickAction() and hasText("Save")).onFirst().performClick()
            waitForIdle()
            // 08's Edit-URL dialog: the failure renders inline and the dialog stays open.
            // The field merges its label with the error, so the Save button is the
            // unambiguous "dialog still open" check.
            onNodeWithText("This isn't a podcast feed").assertIsDisplayed()
            onAllNodes(hasClickAction() and hasText("Save")).onFirst().assertIsDisplayed()
        }

    @Test
    fun neverFetchedShowsNeverAlone() =
        runComposeUiTest {
            setSettings(
                PodcastSettingsUiState(
                    detail = testPodcastDetail(7),
                    feedInfo = testFeedInfo(lastSuccessAt = null, lastErrorKind = null),
                    loaded = true,
                ),
            )
            // A feed that never fetched has no outcome to report — "Never" alone, not a
            // contradictory "Never · OK".
            onNodeWithText("Never").assertIsDisplayed()
            onNodeWithText("Never · OK").assertDoesNotExist()
        }

    @Test
    fun credentialsDialogMasksThePasswordUntilToggled() =
        runComposeUiTest {
            setSettingsCredentialsDialog()

            val password = onAllNodes(hasSetTextAction())[1]
            password.performTextInput("hunter2")

            // 08's dialogs table ("masked, show toggle"): the secret stays transformed —
            // the Password semantics marker is set and the field's text is the mask, not
            // the raw password — until the trailing toggle reveals it. The marker stays
            // set after revealing: it tracks the password keyboard, not the mask.
            password.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            password.assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString("•••••••"),
                ),
            )
            onNodeWithContentDescription("Show password").assertIsDisplayed()

            onNodeWithContentDescription("Show password").performClick()
            password.assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString("hunter2"),
                ),
            )
            onNodeWithContentDescription("Hide password").assertIsDisplayed()
        }

    /**
     * Secrets never enter saved state: after a save/restore round trip the username comes
     * back but the password field starts empty.
     */
    @Test
    fun credentialsDialogRestoresUsernameButNotPassword() =
        runComposeUiTest {
            var restored: Map<String, List<Any?>> = emptyMap()
            var registry = SaveableStateRegistry(restored) { true }
            setSettingsCredentialsDialog(registry = registry)

            onAllNodes(hasSetTextAction())[0].performTextInput("alice")
            onAllNodes(hasSetTextAction())[1].performTextInput("hunter2")

            runOnIdle { restored = registry.performSave() }
            registry = SaveableStateRegistry(restored) { true }
            setSettingsCredentialsDialog(registry = registry)

            val fields = onAllNodes(hasSetTextAction())
            fields[0].assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString("alice"),
                ),
            )
            fields[1].assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString(""),
                ),
            )
        }

    @Test
    fun youtubeFeedHidesTheRssAccountControls() =
        runComposeUiTest {
            setSettings(
                PodcastSettingsUiState(
                    detail = testPodcastDetail(7, sourceType = SourceType.YOUTUBE_CHANNEL),
                    feedInfo = testFeedInfo(),
                    loaded = true,
                ),
            )
            // A YouTube channel has no feed URL to move and no Basic-auth credential (03).
            onNodeWithText("Edit feed address").assertDoesNotExist()
            onNodeWithText("Username and password").assertDoesNotExist()
        }

    private fun ComposeUiTest.setSettings(
        state: PodcastSettingsUiState,
        onCustomTitle: (String?) -> Unit = {},
        onOrderChange: (FeedOrder) -> Unit = {},
        onIncludeInAll: (Boolean) -> Unit = {},
        onEditFeedUrl: suspend (String) -> UiText? = { null },
        onCredentials: (BasicCredentials) -> Unit = {},
    ) {
        setContent {
            CompositionLocalProvider(
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalUiClock provides TestClock(),
                LocalAppNavigator provides navigator,
                LocalPlatformActions provides TestPlatformActions,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    PodcastSettingsScreen(
                        state = state,
                        onCustomTitle = onCustomTitle,
                        onOrderChange = onOrderChange,
                        onIncludeInAll = onIncludeInAll,
                        onEditFeedUrl = onEditFeedUrl,
                        onCredentials = onCredentials,
                    )
                }
            }
        }
        waitForIdle()
    }

    /** The "Username and password" dialog rendered directly. */
    private fun ComposeUiTest.setSettingsCredentialsDialog(
        registry: SaveableStateRegistry = SaveableStateRegistry(null) { true },
        onSubmit: (BasicCredentials) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        setContent {
            CompositionLocalProvider(
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalUiClock provides TestClock(),
                LocalAppNavigator provides navigator,
                LocalPlatformActions provides TestPlatformActions,
                LocalSaveableStateRegistry provides registry,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    SettingsCredentialsDialog(onSubmit = onSubmit, onDismiss = onDismiss)
                }
            }
        }
        waitForIdle()
    }
}

/** The shells' `PlatformActions` contract on the desktop test composition. */
private val TestPlatformActions =
    object : PlatformActions {
        override val urls: ExternalUrlOpener = ExternalUrlOpener { OpenResult.OPENED }
        override val share = null
        override val files: FilePicker =
            object : FilePicker {
                override suspend fun pickFile(
                    mimeTypes: List<String>,
                    extensions: List<String>,
                ): String? = null

                override suspend fun pickFolder(title: String): String? = null
            }
        override val saver: FileSaver = FileSaver { _, _ -> null }
        override val reveal = null
        override val notifications = null
    }
