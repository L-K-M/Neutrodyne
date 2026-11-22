// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.testing.FakeBackupRepository
import ch.lkmc.neutrodyne.core.testing.FakeLinkFlow
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.FileSaver
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import ch.lkmc.neutrodyne.sync.api.DiscoveredServer
import ch.lkmc.neutrodyne.sync.api.FirstLinkChoice
import ch.lkmc.neutrodyne.sync.api.LinkMethod
import ch.lkmc.neutrodyne.sync.api.LinkPhase
import ch.lkmc.neutrodyne.sync.api.LinkState
import ch.lkmc.neutrodyne.sync.api.SyncDisclosure
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Linking on a narrow, short page: a phone's cabinet above the docked mini player (08 Linking a device). */
@OptIn(ExperimentalTestApi::class)
class SyncSetupLayoutTest {
    private lateinit var previousLocale: Locale

    @BeforeTest
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun aStepOfUnknownLengthShowsActivityInsteadOfAnEmptyCount() =
        runDesktopComposeUiTest(width = PAGE_WIDTH, height = PAGE_HEIGHT) {
            setPage(LinkState.Merging(LinkPhase.DOWNLOADING, done = 0, total = 0))

            onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo.Indeterminate,
                ),
            ).assertIsDisplayed()
        }

    @Test
    fun everyMethodFitsOnOneAlignedRow() =
        runDesktopComposeUiTest(width = PAGE_WIDTH, height = PAGE_HEIGHT) {
            setPage(awaitingInput())

            val rows = listOf("Link code", "Invite code", "Password").map { method(it).fetchSemanticsNode() }

            assertEquals(1, rows.map { it.positionInRoot.x }.distinct().size, "the methods share a left edge")
            assertEquals(1, rows.map { it.size.width }.distinct().size, "the methods share a width")
            rows.forEach { assertTrue(it.size.height <= SINGLE_LINE_MAX_HEIGHT, "a method wrapped: ${it.size}") }
        }

    @Test
    fun theCodeActionCanBeReached() =
        runDesktopComposeUiTest(width = PAGE_WIDTH, height = PAGE_HEIGHT) {
            val controller = setPage(awaitingInput())

            onNodeWithText("Get a code").performScrollTo().assertIsDisplayed().performClick()

            assertEquals(listOf(ROUTE to LinkMethod.CODE), controller.startLinks)
        }

    @Test
    fun theInviteFormCanBeReached() =
        runDesktopComposeUiTest(width = PAGE_WIDTH, height = PAGE_HEIGHT) {
            setPage(awaitingInput())

            method("Invite code").performScrollTo().performClick()

            onNodeWithText("Invite code (16 characters)").performScrollTo().assertIsDisplayed()
            onNodeWithText("Continue").performScrollTo().assertIsDisplayed()
        }

    @Test
    fun thePasswordFormCanBeReachedWithLargeGermanText() =
        runDesktopComposeUiTest(width = PAGE_WIDTH, height = PAGE_HEIGHT) {
            Locale.setDefault(Locale.GERMAN)
            setPage(awaitingInput(), fontScale = 2f)

            method("Passwort").performScrollTo().performClick()

            onNodeWithText("Weiter").performScrollTo().assertIsDisplayed().assertHasClickAction()
        }

    @Test
    fun theFirstLinkChoicesScrollToTheirContinueAction() =
        runDesktopComposeUiTest(width = PAGE_WIDTH, height = PAGE_HEIGHT) {
            val controller = setPage(LinkState.ChooseFirstLink(3, 2, 4, 1))

            onNodeWithText("Continue").performScrollTo().assertIsDisplayed().performClick()

            assertEquals(listOf(FirstLinkChoice.MERGE), controller.firstLinkChoices)
        }

    @Test
    fun theServersHyphenatedCodeShowsAsTwoGroupsOfFour() =
        runDesktopComposeUiTest(width = PAGE_WIDTH, height = PAGE_HEIGHT) {
            setPage(LinkState.ShowCode("WDJB-MJHT", "$ROUTE/link", 0L))

            onNodeWithText("WDJB-MJHT").assertIsDisplayed()
            onNodeWithContentDescription("W D J B, M J H T").assertExists()
        }

    @Test
    fun doneLeavesTheFinishedLink() =
        runDesktopComposeUiTest(width = PAGE_WIDTH, height = PAGE_HEIGHT) {
            val navigator = RecordingAppNavigator()
            setPage(LinkState.Linked("anna", "Pixel 8"), navigator = navigator)

            onNodeWithText("Done").performScrollTo().performClick()

            assertEquals(listOf<RecordingAppNavigator.Call>(RecordingAppNavigator.Call.Pop), navigator.calls)
        }

    @Test
    fun theFinishedLinkNamesTheServerAndTheDevice() =
        runDesktopComposeUiTest(width = PAGE_WIDTH, height = PAGE_HEIGHT) {
            setPage(LinkState.Linked("anna", "Pixel 8"))

            onNodeWithText("This device is linked to sync.example.org as Pixel 8.").assertExists()
        }

    @Test
    fun onePrivateFeedLinkIsSingular() =
        runDesktopComposeUiTest(width = PAGE_WIDTH, height = PAGE_HEIGHT) {
            setPage(awaitingInput(privateFeedLinks = 1))

            onNodeWithText("including 1 private feed link,", substring = true).assertExists()
        }

    @Test
    fun onePrivateFeedLinkIsSingularInGerman() =
        runDesktopComposeUiTest(width = PAGE_WIDTH, height = PAGE_HEIGHT) {
            Locale.setDefault(Locale.GERMAN)
            setPage(awaitingInput(privateFeedLinks = 1))

            onNodeWithText("darunter 1 privater Feed-Link,", substring = true).assertExists()
        }

    private fun ComposeUiTest.method(label: String) =
        onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))

    private fun ComposeUiTest.setPage(
        state: LinkState,
        fontScale: Float = 1f,
        navigator: RecordingAppNavigator = RecordingAppNavigator(),
    ): FakeSyncController {
        val controller = FakeSyncController(link = FakeLinkFlow(state))
        val backup = FakeBackupRepository()
        setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides ResumedLifecycleOwner(),
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalPlatformActions provides NoPlatformActions,
                LocalAppNavigator provides navigator,
                LocalDensity provides Density(1f, fontScale),
            ) {
                NeutrodyneTheme(SystemUiState.DEFAULT) {
                    SyncSetupScreen(controller, ROUTE, backup)
                }
            }
        }
        waitForIdle()
        return controller
    }

    private class ResumedLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)

        init {
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    private object NoPlatformActions : PlatformActions {
        override val urls = ExternalUrlOpener { OpenResult.OPENED }
        override val share = null
        override val files =
            object : FilePicker {
                override suspend fun pickFile(
                    mimeTypes: List<String>,
                    extensions: List<String>,
                ): String? = null

                override suspend fun pickFolder(title: String): String? = null
            }

        override val saver = FileSaver { _, _ -> null }
        override val reveal = null
        override val notifications = null
    }

    private companion object {
        const val ROUTE = "https://sync.example.org"

        // The reported phone's paper in dp: 921 x 1520 px at 460 dpi.
        const val PAGE_WIDTH = 320
        const val PAGE_HEIGHT = 528
        const val SINGLE_LINE_MAX_HEIGHT = 56

        fun awaitingInput(privateFeedLinks: Int = 0): LinkState.AwaitingInput =
            LinkState.AwaitingInput(
                method = LinkMethod.CODE,
                server = DiscoveredServer("Sync", "1", ROUTE, emptyList(), 10, 0),
                disclosure = SyncDisclosure("sync.example.org", privateFeedLinks, encrypted = true),
            )
    }
}
