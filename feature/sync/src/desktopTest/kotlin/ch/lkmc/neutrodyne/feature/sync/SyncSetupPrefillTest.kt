// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.theme.AppearancePrefs
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.SystemUiState
import ch.lkmc.neutrodyne.core.domain.SyncServerAddress
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.navigation.SyncSetupKey
import ch.lkmc.neutrodyne.core.testing.FakeBackupRepository
import ch.lkmc.neutrodyne.core.testing.FakeLinkFlow
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.core.testing.FakeSyncServerAddress
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.FileSaver
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import ch.lkmc.neutrodyne.sync.api.DiscoveredServer
import ch.lkmc.neutrodyne.sync.api.LinkMethod
import ch.lkmc.neutrodyne.sync.api.LinkState
import ch.lkmc.neutrodyne.sync.api.SyncDisclosure
import dev.zacsweers.metro.Provider
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reconnect prefill (08 Settings › Sync, 10 Relinking): a stored `sync.username` is offered
 * only for the same server URL, path included, and only until the user chooses a method or a
 * username. A late read never overwrites either; leaving the screen cancels it. An unknown
 * username and a different server leave the manual methods untouched, and a failed settings read
 * keeps the form usable with an actionable Retry. Editing the address to another server drops
 * only the auto-filled identity, never a method or username the user chose.
 */
@OptIn(ExperimentalTestApi::class)
class SyncSetupPrefillTest {
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
    fun matchingStoredUsernameSelectsPasswordAndPrefillsIt() =
        runComposeUiTest {
            val harness = Harness()
            harness.gate.complete(Unit)
            setPage(harness)
            waitUntil(timeoutMillis = 5_000) { harness.address.readStarted }
            waitForIdle()

            onNodeWithText("Username").assertIsDisplayed()
            onNodeWithText("anna").assertIsDisplayed()
            onNodeWithText("Get a code").assertDoesNotExist()
        }

    @Test
    fun unknownUsernameLeavesCodeSelected() =
        runComposeUiTest {
            val harness = Harness(username = null)
            harness.gate.complete(Unit)
            setPage(harness)
            waitUntil(timeoutMillis = 5_000) { harness.address.readStarted }
            waitForIdle()

            onNodeWithText("Get a code").assertIsDisplayed()
            onNodeWithText("Username").assertDoesNotExist()
        }

    @Test
    fun aDifferentServerKeepsItsOwnInputs() =
        runComposeUiTest {
            val harness = Harness(serverUrl = "https://other.example")
            harness.gate.complete(Unit)
            setPage(harness)
            waitUntil(timeoutMillis = 5_000) { harness.address.readStarted }
            waitForIdle()

            onNodeWithText("Get a code").assertIsDisplayed()
            onNodeWithText("Username").assertDoesNotExist()
        }

    @Test
    fun aDifferentPathIsNotTheSameServer() =
        runComposeUiTest {
            val harness = Harness(serverUrl = "https://sync.example.org/prefix")
            harness.gate.complete(Unit)
            setPage(harness)
            waitUntil(timeoutMillis = 5_000) { harness.address.readStarted }
            waitForIdle()

            onNodeWithText("Get a code").assertIsDisplayed()
            onNodeWithText("Username").assertDoesNotExist()
        }

    @Test
    fun editingTheAddressClearsTheAutoFilledIdentity() =
        runComposeUiTest {
            val harness = Harness(initialState = LinkState.Address)
            harness.gate.complete(Unit)
            setPage(harness)
            waitUntil(timeoutMillis = 5_000) { harness.address.readStarted }
            waitForIdle()

            onNode(hasSetTextAction()).performTextReplacement("https://other.example")
            waitForIdle()
            onNodeWithText("Continue").performClick()
            waitForIdle()
            harness.link.setState(awaitingInput("https://other.example"))
            waitForIdle()

            onNodeWithText("Get a code").assertIsDisplayed()
            onNodeWithText("Username").assertDoesNotExist()
            assertEquals(listOf("https://other.example"), harness.controller.discoveries)
        }

    @Test
    fun editingTheAddressKeepsExplicitManualInputs() =
        runComposeUiTest {
            val harness = Harness()
            setPage(harness)
            waitUntil(timeoutMillis = 5_000) { harness.address.readStarted }

            onNodeWithText("Password").performClick()
            waitForIdle()
            onAllNodes(hasSetTextAction())[0].performTextReplacement("bob")
            waitForIdle()
            harness.gate.complete(Unit)
            waitForIdle()

            harness.link.setState(LinkState.Address)
            waitForIdle()
            onNode(hasSetTextAction()).performTextReplacement("https://other.example")
            waitForIdle()
            onNodeWithText("Continue").performClick()
            waitForIdle()
            harness.link.setState(awaitingInput("https://other.example"))
            waitForIdle()

            onNodeWithText("Username").assertIsDisplayed()
            onNodeWithText("bob").assertIsDisplayed()
        }

    @Test
    fun aManualMethodChoiceWinsOverTheLateRead() =
        runComposeUiTest {
            val harness = Harness()
            setPage(harness)
            waitUntil(timeoutMillis = 5_000) { harness.address.readStarted }

            onNodeWithText("Invite code").performClick()
            waitForIdle()
            harness.gate.complete(Unit)
            waitForIdle()

            onNodeWithText("Invite code (16 characters)").assertIsDisplayed()
            onNodeWithText("Username").assertDoesNotExist()
        }

    @Test
    fun aManualUsernameWinsOverTheLateRead() =
        runComposeUiTest {
            val harness = Harness()
            setPage(harness)
            waitUntil(timeoutMillis = 5_000) { harness.address.readStarted }

            onNodeWithText("Password").performClick()
            waitForIdle()
            onAllNodes(hasSetTextAction())[0].performTextInput("bob")
            waitForIdle()
            harness.gate.complete(Unit)
            waitForIdle()

            onNodeWithText("bob").assertIsDisplayed()
            onNodeWithText("anna").assertDoesNotExist()
        }

    @Test
    fun leavingTheScreenCancelsTheRead() =
        runComposeUiTest {
            val harness = Harness()
            setPage(harness)
            waitUntil(timeoutMillis = 5_000) { harness.address.readStarted }

            harness.show = false
            waitForIdle()
            harness.gate.complete(Unit)
            waitForIdle()

            assertTrue(harness.address.readCancelled, "the read must be cancelled with the screen")
        }

    @Test
    fun aFailedReadKeepsTheManualFormAndRetryWorks() =
        runComposeUiTest {
            val harness = Harness(failFirstRead = true)
            harness.gate.complete(Unit)
            setPage(harness)
            waitUntil(timeoutMillis = 5_000) { harness.address.readStarted }
            waitForIdle()

            onNodeWithText("Couldn't read the saved username. Enter it manually, or try again.")
                .assertIsDisplayed()
            onNodeWithText("Get a code").assertIsDisplayed()

            onNodeWithText("Retry").performClick()
            waitForIdle()

            onNodeWithText("Username").assertIsDisplayed()
            onNodeWithText("anna").assertIsDisplayed()
        }

    @Test
    fun theProductionEntryWiresTheAddressPort() =
        runComposeUiTest {
            val controller = FakeSyncController(link = FakeLinkFlow(awaitingInput()))
            val address = FakeSyncServerAddress(ROUTE, "anna")
            val installer =
                SyncNavigation.entries(
                    Provider { controller },
                    Provider { FakeBackupRepository() },
                    Provider { address },
                    testBuildInfo(),
                )
            val provider = entryProvider<NavKey> { installer() }

            setContent {
                CompositionLocalProvider(
                    LocalLifecycleOwner provides ResumedLifecycleOwner(),
                    LocalPlatformKind provides PlatformKind.DESKTOP,
                    LocalPlatformActions provides testActions(),
                ) {
                    NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                        provider(SyncSetupKey(ROUTE)).Content()
                    }
                }
            }
            waitForIdle()

            onNodeWithText("Username").assertIsDisplayed()
            onNodeWithText("anna").assertIsDisplayed()
        }

    private fun ComposeUiTest.setPage(harness: Harness) {
        setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides ResumedLifecycleOwner(),
                LocalPlatformKind provides PlatformKind.DESKTOP,
                LocalPlatformActions provides harness.actions,
            ) {
                NeutrodyneTheme(AppearancePrefs(), SystemUiState.DEFAULT) {
                    if (harness.show) {
                        SyncSetupScreen(
                            harness.controller,
                            ROUTE,
                            harness.backup,
                            addressPort = harness.address,
                        )
                    }
                }
            }
        }
        waitForIdle()
    }

    private class ResumedLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)

        init {
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    private class Harness(
        serverUrl: String? = ROUTE,
        username: String? = "anna",
        val gate: CompletableDeferred<Unit> = CompletableDeferred(),
        failFirstRead: Boolean = false,
        initialState: LinkState = awaitingInput(),
    ) {
        val link = FakeLinkFlow(initialState)
        val controller = FakeSyncController(link = link)
        val backup = FakeBackupRepository()
        val address =
            GatedSyncServerAddress(FakeSyncServerAddress(serverUrl, username), gate)
                .apply {
                    if (failFirstRead) {
                        failNextRead = true
                    }
                }

        var show by mutableStateOf(true)

        val actions = testActions()
    }

    /** A [SyncServerAddress] whose reads wait for [gate], so a test can finish them by hand. */
    private class GatedSyncServerAddress(
        private val delegate: FakeSyncServerAddress,
        private val gate: CompletableDeferred<Unit>,
    ) : SyncServerAddress by delegate {
        var readStarted: Boolean = false
            private set

        var readCancelled: Boolean = false
            private set

        var failNextRead: Boolean = false

        override suspend fun serverUrl(): String? {
            readStarted = true
            try {
                gate.await()
            } catch (cancelled: CancellationException) {
                readCancelled = true
                throw cancelled
            }
            if (failNextRead) {
                failNextRead = false
                throw IllegalStateException("settings read failed")
            }
            return delegate.serverUrl()
        }
    }

    private companion object {
        const val ROUTE = "https://sync.example.org"

        fun awaitingInput(serverUrl: String = ROUTE): LinkState.AwaitingInput =
            LinkState.AwaitingInput(
                method = LinkMethod.CODE,
                server =
                    DiscoveredServer(
                        name = "Sync",
                        serverVersion = "1",
                        serverUrl = serverUrl,
                        features = emptyList(),
                        maxBatch = 10,
                        maxSkewMs = 0,
                    ),
                disclosure =
                    SyncDisclosure(host = hostOf(serverUrl), privateFeedLinks = 0, encrypted = true),
            )

        fun testBuildInfo(): BuildInfo =
            BuildInfo(
                versionName = "1.0.0",
                versionCode = 1,
                debug = false,
                platform = BuildInfo.Platform.DESKTOP,
                repoUrl = "https://github.com/L-K-M/Neutrodyne",
                updateManifestUrl = "https://example.com/update.json",
                engineManifestUrl = "https://example.com/engine.json",
                youTubeEngineBundled = true,
                shippedLocales = persistentListOf("en"),
                podcastIndexKey = "",
                podcastIndexSecret = "",
            )

        fun testActions(): PlatformActions =
            object : PlatformActions {
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
    }
}
