// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.runComposeUiTest
import ch.lkmc.neutrodyne.core.designsystem.components.NdButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdEmptyState
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.ExportKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.FileSaver
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.nav_feeds
import ch.lkmc.neutrodyne.core.ui.resources.nav_library
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * S9's `runComposeUiTest` checks of `NeutrodyneRoot` on the desktop JVM (01 S9): the five labelled
 * destinations, per-tab state keeping, Settings open/back, a sheet and a dialog above content,
 * Escape as back, and S11's locale switch. The destination entries are test stubs — the feature
 * modules' own installers look identical.
 */
@OptIn(ExperimentalTestApi::class)
class NeutrodyneRootTest {
    private lateinit var previousLocale: Locale

    @BeforeTest
    fun setUp() {
        // The shared test-task config sets the JVM to de_DE; these assertions name English labels.
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun fiveDestinationsAreLabelled() =
        runComposeUiTest {
            setRootContent()
            // The labels appear in the rail items (tagged nav_*) and again in the stubs' top bars.
            for (tag in listOf("nav_feeds", "nav_library", "nav_up_next", "nav_downloads", "nav_discover")) {
                onNodeWithTag(tag).assertIsDisplayed()
            }
            onNodeWithText("feeds-content").assertIsDisplayed()
        }

    @Test
    fun tabSwitchKeepsEntryState() =
        runComposeUiTest {
            setRootContent()
            onNodeWithTag("nav_library").performClick()
            onNodeWithText("inc").performClick()
            onNodeWithText("count:1").assertIsDisplayed()

            // Library's entries leave composition entirely; rememberSaveable must restore the count.
            onNodeWithTag("nav_feeds").performClick()
            onNodeWithText("count:1").assertDoesNotExist()

            onNodeWithTag("nav_library").performClick()
            onNodeWithText("count:1").assertIsDisplayed()
        }

    @Test
    fun settingsOpensAndBackReturns() =
        runComposeUiTest {
            setRootContent()
            // Two gears exist in a rail suite: the footer gear and the stub's top-bar one.
            onAllNodesWithContentDescription("Settings").onFirst().performClick()
            onNodeWithText("settings-home").assertIsDisplayed()

            pressEscape()
            onNodeWithText("settings-home").assertDoesNotExist()
            onNodeWithText("feeds-content").assertIsDisplayed()
        }

    @Test
    fun sheetRendersAboveContent() =
        runComposeUiTest {
            setRootContent()
            onNodeWithText("open-sheet").performClick()
            onNodeWithText("add-podcast-sheet").assertIsDisplayed()
            onNodeWithText("feeds-content").assertIsDisplayed()
        }

    @Test
    fun dialogRendersAboveContent() =
        runComposeUiTest {
            setRootContent()
            onNodeWithText("open-dialog").performClick()
            onNodeWithText("export-dialog").assertIsDisplayed()
            onNodeWithText("feeds-content").assertIsDisplayed()
        }

    @Test
    fun escapePopsAnEntry() =
        runComposeUiTest {
            setRootContent()
            onNodeWithText("open-podcast").performClick()
            waitForIdle()
            onNodeWithText("podcast-3").assertIsDisplayed()

            pressEscape()
            onNodeWithText("podcast-3").assertDoesNotExist()
            onNodeWithText("feeds-content").assertIsDisplayed()
        }

    @Test
    fun localeSwitchRelabelsTheSuite() =
        runComposeUiTest {
            var localeTag by mutableStateOf("")
            setContent {
                key(localeTag) { TestRoot() }
            }
            onNodeWithText("Library").assertIsDisplayed()

            runOnIdle {
                Locale.setDefault(Locale.GERMAN)
                localeTag = "de"
            }
            onNodeWithText("Bibliothek").assertIsDisplayed()
            onNodeWithText("Als Nächstes").assertIsDisplayed()
        }

    private fun ComposeUiTest.setRootContent() {
        setContent { TestRoot() }
    }

    private fun ComposeUiTest.pressEscape() {
        // Key events dispatch through the focused chain up to the tagged box's onKeyEvent.
        onNodeWithTag("root-box").performKeyInput {
            keyDown(Key.Escape)
            keyUp(Key.Escape)
        }
        waitForIdle()
    }
}

/** Minimal `PlatformActions` for the desktop test composition (the shells' contract). */
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

private fun testActions(): RootActions =
    RootActions(
        retryStartup = {},
        dismissNotice = {},
        continueHere = {},
        dismissRemoteSession = {},
        playbackKey = { false },
    )

private val TestSlots = RootSlots(player = {}, userMessages = emptyFlow())

/**
 * The root under test with the shells' `LocalPlatformActions` in place, exactly as `NeutrodyneWindow`
 * composes it.
 */
@Composable
private fun TestRoot() {
    CompositionLocalProvider(LocalPlatformActions provides TestPlatformActions) {
        NeutrodyneRoot(
            state = RootUiState.READY,
            actions = testActions(),
            slots = TestSlots,
            installers = TestInstallers,
            platform = BuildInfo.Platform.DESKTOP,
            modifier = Modifier.testTag("root-box"),
        )
    }
}

private val TestInstallers: Set<EntryProviderInstaller> =
    buildSet {
        add { entry<FeedsKey>(metadata = NdSceneMetadata.paneList()) { FeedsStub() } }
        add { entry<LibraryKey>(metadata = NdSceneMetadata.paneList()) { LibraryStub() } }
        add {
            entry<UpNextKey>(metadata = NdSceneMetadata.paneList()) {
                TabStub("up-next-content", NdIcons.QueueMusic)
            }
        }
        add {
            entry<DownloadsKey>(metadata = NdSceneMetadata.paneList()) {
                TabStub("downloads-content", NdIcons.Download)
            }
        }
        add {
            entry<DiscoverKey>(metadata = NdSceneMetadata.paneList()) {
                TabStub("discover-content", NdIcons.Explore)
            }
        }
        add { entry<SettingsHomeKey>(metadata = NdSceneMetadata.paneList()) { Text("settings-home") } }
        add {
            entry<PodcastKey>(metadata = NdSceneMetadata.paneDetail()) { key ->
                Text("podcast-${key.podcastId}")
            }
        }
        add { entry<AddPodcastKey>(metadata = NdSceneMetadata.bottomSheet()) { Text("add-podcast-sheet") } }
        add { entry<ExportKey>(metadata = NdSceneMetadata.dialog()) { Text("export-dialog") } }
    }

@Composable
private fun FeedsStub() {
    val navigator = LocalAppNavigator.current
    Column(Modifier.fillMaxSize()) {
        TestTopBar(Res.string.nav_feeds)
        Text("feeds-content")
        NdButton(label = "open-sheet", onClick = { navigator.push(AddPodcastKey(null)) })
        NdButton(label = "open-dialog", onClick = { navigator.push(ExportKey(null)) })
        NdButton(label = "open-podcast", onClick = { navigator.push(PodcastKey(3)) })
    }
}

@Composable
private fun LibraryStub() {
    var count by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TestTopBar(Res.string.nav_library)
        Text("count:$count")
        NdButton(label = "inc", onClick = { count++ })
    }
}

@Composable
private fun TabStub(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Column(Modifier.fillMaxSize()) {
        NdEmptyState(icon = icon, title = text, body = text)
    }
}

@Composable
private fun TestTopBar(title: StringResource) {
    NdTopAppBar(
        title = stringResource(title),
        actions = { SettingsGearButton() },
    )
}
