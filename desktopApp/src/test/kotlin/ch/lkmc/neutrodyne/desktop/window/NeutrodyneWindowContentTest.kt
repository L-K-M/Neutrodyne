// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation3.runtime.NavKey
import ch.lkmc.neutrodyne.core.common.DesktopNotification
import ch.lkmc.neutrodyne.core.common.NotificationKind
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.model.settings.UpdateSettingKeys
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckState
import ch.lkmc.neutrodyne.core.model.update.UpdateInfo
import ch.lkmc.neutrodyne.core.model.update.UpdateNotice
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.Route
import ch.lkmc.neutrodyne.core.testing.FakeAppUpdateChecker
import ch.lkmc.neutrodyne.core.testing.FakeCrashReporter
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSyncController
import ch.lkmc.neutrodyne.core.testing.FakeUpdateNotices
import ch.lkmc.neutrodyne.core.testing.testNowPlaying
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.RevealInFolder
import ch.lkmc.neutrodyne.core.ui.root.ReconnectProfile
import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashOffer
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.crash.crashDialogBuildInfo
import ch.lkmc.neutrodyne.desktop.crash.writeCrashFile
import ch.lkmc.neutrodyne.desktop.di.createDesktopGraph
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.notifications.DesktopBannerNotifier
import ch.lkmc.neutrodyne.desktop.platform.DesktopClock
import ch.lkmc.neutrodyne.desktop.shell.tempAppDirs
import ch.lkmc.neutrodyne.sync.api.RemoteSessionOffer
import ch.lkmc.neutrodyne.sync.api.SyncNotice
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.Locale

/**
 * The window's content on the desktop JVM (11 Testing's window cases; 01 S9's method): the five
 * labelled destinations of the real feature installers, Settings › About and Licences, with the
 * desktop [ch.lkmc.neutrodyne.desktop.ui.DesktopLicencesSource] reading the build's generated
 * `aboutlibraries.json` — so the OpenJDK runtime entry of the manual config is asserted against
 * the very resource a packaged Licences screen shows.
 */
@OptIn(ExperimentalTestApi::class)
class NeutrodyneWindowContentTest {
    private lateinit var previousLocale: Locale

    private val graph =
        tempAppDirs()
            .let { dirs ->
                dirs.ensureCreated()
                val buildInfo = BuildInfoLoader.load()
                createDesktopGraph(
                    dirs,
                    buildInfo,
                    DesktopCrashReporter(dirs, buildInfo, DesktopClock, RecentLogBuffer()),
                )
            }

    @Before
    fun setUp() {
        // The shared test-task config sets the JVM to de_DE; these assertions name English labels.
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun tearDown() {
        graph.appScope.cancel()
        Locale.setDefault(previousLocale)
    }

    @Test
    fun radioMenuLabelsEveryDestination() =
        runComposeUiTest {
            setWindowContent()

            onNodeWithTag("radio_menu").performClick()
            for (tag in listOf("nav_library", "nav_up_next", "nav_downloads", "nav_discover", "nav_settings")) {
                onNodeWithTag(tag).assertIsDisplayed()
            }
        }

    /**
     * The VM-backed Library entry through the window's real locals (01 Feature entry installers):
     * `metroViewModel()` inside `LibraryRoute` only resolves because the content provides
     * `LocalMetroViewModelFactory`, and the screen reaches its loaded state only when the
     * graph's repositories answer — graph compilation alone proves neither.
     */
    @Test
    fun libraryOpensThroughTheMetroViewModelFactory() =
        runComposeUiTest {
            // The VM's repositories come from the graph's database accessor, which gates on the open.
            runBlocking { graph.databaseOpener.awaitOpen() }
            setWindowContent()

            onNodeWithTag("radio_menu").performClick()
            onNodeWithTag("nav_library").performClick()
            waitUntil("the Library ViewModel loads", TIMEOUT_MS) {
                onAllNodesWithText("Your feeds live here").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Search podcasts").assertIsDisplayed()
        }

    @Test
    fun settingsAboutShowsTheBuildIdentity() =
        runComposeUiTest {
            setWindowContent()

            // Two gears exist in a rail suite: the footer's and the feeds stub's top-bar one.
            onNodeWithTag("radio_menu").performClick()
            onNodeWithTag("nav_settings").performClick()
            onNode(
                androidx.compose.ui.test
                    .hasText("Appearance") and
                    androidx.compose.ui.test
                        .hasClickAction(),
            ).assertExists()

            onAllNodesWithText("About").onFirst().performScrollTo().performClick()
            onNodeWithText("Neutrodyne").assertExists()
            onNodeWithText("Debug build").assertExists()
        }

    @Test
    fun updateBadgeAndDisclosureObserveServicesAndTurnOffPersists() =
        runComposeUiTest {
            runBlocking { graph.databaseOpener.awaitOpen() }
            val checker = FakeAppUpdateChecker(UpdateCheckState.Available(UPDATE_INFO, 0))
            val notices = FakeUpdateNotices(listOf(UpdateNotice.FIRST_RUN_CHOICE))
            val settings = FakeSettingsRepository()
            setContent {
                NeutrodyneWindowContent(
                    installers = graph.entryInstallers,
                    menuActions = DesktopMenuActions(),
                    viewModelFactory = graph.metroViewModelFactory,
                    playbackController = graph.playbackController,
                    playbackState = graph.playbackStateSource,
                    updateChecker = checker,
                    updateNotices = notices,
                    settings = settings,
                )
            }
            onAllNodesWithContentDescription("Settings, update available", substring = true).onFirst().assertExists()
            onNodeWithText("Turn off").performClick()
            waitUntil("the disclosure is durably dismissed", TIMEOUT_MS) { notices.pending.value == null }
            assertThat(runBlocking { settings.get(UpdateSettingKeys.CHECK_ENABLED) }).isFalse()
            runOnIdle { checker.state.value = UpdateCheckState.Idle(0) }
            onAllNodesWithContentDescription("Settings, update available", substring = true).assertCountEquals(0)
            onNodeWithText("Turn off").assertDoesNotExist()
        }

    @Test
    fun updateSettingsAndBundledHelpOpenThroughTheProductionGraph() =
        runComposeUiTest {
            setWindowContent()
            onNodeWithTag("radio_menu").performClick()
            onNodeWithTag("nav_settings").performClick()
            onNodeWithText("Updates").performClick()
            onNodeWithTag("updates-page").assertExists()
            onNodeWithText("Install & updates").performScrollTo().performClick()
            onNodeWithTag("install-help-page").assertExists()
        }

    /**
     * The window must provide `LocalPlatformKind` as DESKTOP (93-4): shared rows pick
     * per-platform copy from it (07's "download folder" wording), and nothing else provides
     * the local above the entries, so a missing provision silently falls back to ANDROID.
     * The probe entry reads the local inside the shell's locals — the menu bridge's push is
     * the navigator captured in the root's player slot.
     */
    @Test
    fun theWindowProvidesTheDesktopPlatformKind() =
        runComposeUiTest {
            // The default (Feeds) tab is VM-backed: the database accessor gates on the open.
            runBlocking { graph.databaseOpener.awaitOpen() }
            var provided: PlatformKind? = null
            val menuActions = DesktopMenuActions()
            setContent {
                NeutrodyneWindowContent(
                    installers = graph.entryInstallers + platformProbeInstaller { provided = it },
                    menuActions = menuActions,
                    viewModelFactory = graph.metroViewModelFactory,
                    playbackController = graph.playbackController,
                    playbackState = graph.playbackStateSource,
                )
            }

            waitUntil("the root navigator attaches", TIMEOUT_MS) {
                menuActions.pushKey(PlatformProbeKey)
            }
            waitUntil("the probe entry composes", TIMEOUT_MS) { provided != null }
            assertThat(provided).isEqualTo(PlatformKind.DESKTOP)
        }

    /**
     * The in-app banner fallback end to end (11 Notifications before MD2's `OsDesktopNotifier`):
     * a posted `DesktopNotification` surfaces on the root snackbar through
     * `RootSlots.userMessages`, and its "Open" tap comes back through `userMessageAction` to the
     * notifier's route dispatch — where the shell's `applyRoute` would take over.
     */
    @Test
    fun aPostedNotificationShowsItsBannerAndAppliesItsRoute() =
        runComposeUiTest {
            runBlocking { graph.databaseOpener.awaitOpen() }
            val banners = DesktopBannerNotifier()
            val dispatched = mutableListOf<Route>()
            banners.routeDispatch = {
                dispatched += it
                true
            }
            setContent {
                NeutrodyneWindowContent(
                    installers = graph.entryInstallers,
                    menuActions = DesktopMenuActions(),
                    viewModelFactory = graph.metroViewModelFactory,
                    playbackController = graph.playbackController,
                    playbackState = graph.playbackStateSource,
                    banners = banners,
                )
            }
            runBlocking { banners.post(IMPORT_NOTIFICATION) }

            waitUntil("the banner shows", TIMEOUT_MS) {
                onAllNodesWithText("Import finished — 3 added").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Open").performClick()

            waitUntil("the tap reaches the dispatch", TIMEOUT_MS) { dispatched.isNotEmpty() }
            assertThat(dispatched.single())
                .isEqualTo(Route.Navigate(LibraryKey, listOf(ImportKey("7"))))
        }

    /**
     * MS3's Continue card through the real window (08 Continue on this device): the service's
     * `remoteSession` offer becomes the card state, Play routes to `PlaybackController.play()`
     * and Dismiss to `dismissRemoteSession()` — the parent wired both callbacks to no-ops and
     * never mapped the offer, so this fails on every shortcut of the real path.
     */
    @Test
    fun theContinueCardMapsResumesAndDismissesThroughTheShellPath() =
        runComposeUiTest {
            // The default (Feeds) tab is VM-backed: the database accessor gates on the open.
            runBlocking { graph.databaseOpener.awaitOpen() }
            val sync = FakeSyncController()
            val playback = FakePlaybackController()
            setContent {
                NeutrodyneWindowContent(
                    installers = graph.entryInstallers,
                    menuActions = DesktopMenuActions(),
                    viewModelFactory = graph.metroViewModelFactory,
                    playbackController = playback,
                    playbackState = playback.state,
                    syncController = { sync },
                )
            }

            runOnIdle { sync.remoteSession.value = OFFER }
            waitUntil("the continue card shows", TIMEOUT_MS) {
                onAllNodesWithText("Continue on this device").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Episode 7 · 23:14 · from Pixel 8").assertExists()

            onNode(hasText("Play") and isEnabled()).performClick()
            waitUntil("the resume reached play()", TIMEOUT_MS) {
                playback.calls.contains(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.PLAY),
                )
            }
            assertThat(sync.remoteSessionDismissals).isEmpty()

            runOnIdle { sync.remoteSession.value = OFFER }
            waitUntil("the card shows again", TIMEOUT_MS) {
                onAllNodesWithText("Continue on this device").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Dismiss").performClick()
            waitUntil("the dismissal reached the service", TIMEOUT_MS) {
                sync.remoteSessionDismissals.size == 1
            }
            waitUntil("the cleared offer hides the card", TIMEOUT_MS) {
                onAllNodesWithText("Continue on this device").fetchSemanticsNodes().isEmpty()
            }
        }

    /**
     * `MarkedPlayedElsewhere` must surface on the root snackbar through the window's merged
     * `userMessages`, beside the notification banners — a notice that dies in the service or a
     * merge that drops either upstream fails here.
     */
    @Test
    fun syncNoticesReachTheShellSnackbarBesideTheBannerFlow() =
        runComposeUiTest {
            runBlocking { graph.databaseOpener.awaitOpen() }
            val sync = FakeSyncController()
            val banners = DesktopBannerNotifier()
            setContent {
                NeutrodyneWindowContent(
                    installers = graph.entryInstallers,
                    menuActions = DesktopMenuActions(),
                    viewModelFactory = graph.metroViewModelFactory,
                    playbackController = FakePlaybackController(),
                    playbackState = graph.playbackStateSource,
                    banners = banners,
                    syncController = { sync },
                )
            }

            runBlocking { banners.post(IMPORT_NOTIFICATION) }
            waitUntil("the banner message shows", TIMEOUT_MS) {
                onAllNodesWithText("Import finished — 3 added").fetchSemanticsNodes().isNotEmpty()
            }

            waitUntil("the notices flow is subscribed", TIMEOUT_MS) {
                sync.noticeSubscriptions.value > 0
            }
            waitUntil("the preceding snackbar finishes", 10_000) {
                onAllNodesWithText("Import finished — 3 added").fetchSemanticsNodes().isEmpty()
            }
            runOnIdle { sync.post(SyncNotice.MarkedPlayedElsewhere(42, "Episode 7", "Pixel 8")) }
            waitUntil("the sync notice shows", TIMEOUT_MS) {
                onAllNodesWithText("Marked played on Pixel 8. It keeps playing here.")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        }

    /**
     * 08: the card takes the mini player's slot while the adopted item stays paused underneath —
     * a loaded-but-paused `NowPlaying` must not suppress it; only an actively playing local
     * session does. The offer itself is never touched by the suppression.
     */
    @Test
    fun aPausedMiniPlayerKeepsTheCardAndOnlyPlayingHidesIt() =
        runComposeUiTest {
            runBlocking { graph.databaseOpener.awaitOpen() }
            val sync = FakeSyncController()
            val playback = FakePlaybackController()
            setContent {
                NeutrodyneWindowContent(
                    installers = graph.entryInstallers,
                    menuActions = DesktopMenuActions(),
                    viewModelFactory = graph.metroViewModelFactory,
                    playbackController = playback,
                    playbackState = playback.state,
                    syncController = { sync },
                )
            }
            runOnIdle {
                playback.state.nowPlaying.value =
                    testNowPlaying().copy(isPlaying = false, playWhenReady = false)
                sync.remoteSession.value = OFFER
            }
            waitUntil("the card shows beside the paused mini-player", TIMEOUT_MS) {
                onAllNodesWithText("Continue on this device").fetchSemanticsNodes().isNotEmpty()
            }

            runOnIdle { playback.state.nowPlaying.value = testNowPlaying() }
            waitUntil("live playback hides the card", TIMEOUT_MS) {
                onAllNodesWithText("Continue on this device").fetchSemanticsNodes().isEmpty()
            }
            assertThat(sync.remoteSession.value).isEqualTo(OFFER)

            runOnIdle {
                playback.state.nowPlaying.value =
                    testNowPlaying().copy(isPlaying = false, playWhenReady = false)
            }
            waitUntil("pausing re-shows the card", TIMEOUT_MS) {
                onAllNodesWithText("Continue on this device").fetchSemanticsNodes().isNotEmpty()
            }
        }

    @Test
    fun licencesListTheOpenJdkRuntime() =
        runComposeUiTest {
            setWindowContent()

            onNodeWithTag("radio_menu").performClick()
            onNodeWithTag("nav_settings").performClick()
            onAllNodesWithText("Licences").onFirst().performScrollTo().performClick()

            // The desktop source loads the generated aboutlibraries.json off the classpath. The
            // list is lazy and the OpenJDK entry sits in "Bundled components" at its end, so the
            // search field filters the list down to it instead of scrolling.
            waitUntil("the licences list loads", TIMEOUT_MS) {
                onAllNodesWithText("Search").fetchSemanticsNodes().isNotEmpty()
            }
            onNode(hasSetTextAction()).performTextInput("Temurin")
            waitUntil("the filtered entry shows", TIMEOUT_MS) {
                onAllNodesWithText("OpenJDK runtime (Temurin 25)").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("OpenJDK runtime (Temurin 25)").assertIsDisplayed()
        }

    @Test
    fun persistedImportsResumeThroughTheRealRootAndMetroGraph() =
        runComposeUiTest {
            runBlocking { graph.databaseOpener.awaitOpen() }
            val source =
                kotlin.io.path
                    .createTempFile("nd-resume", ".opml")
                    .toFile()
            try {
                source.writeText(
                    """<opml version="2.0"><body>
                <outline text="Test show" xmlUrl="https://example.com/feed"/>
                </body></opml>""",
                )
                runBlocking {
                    repeat(2) {
                        val result =
                            graph.importRepository.create(
                                ch.lkmc.neutrodyne.core.model
                                    .ImportSource(source.toURI().toString(), "feeds-$it.opml"),
                            )
                        assertThat(result).isInstanceOf(ch.lkmc.neutrodyne.core.common.Outcome.Success::class.java)
                    }
                }
                setWindowContent()
                waitUntil("the import resume banner loads", TIMEOUT_MS) {
                    onAllNodesWithText("Finish importing 1 podcast").fetchSemanticsNodes().isNotEmpty()
                }
                onAllNodesWithText("Finish importing 1 podcast").assertCountEquals(1)
                onNodeWithText("Review").performClick()
                waitUntil("the persisted preview opens", TIMEOUT_MS) {
                    onAllNodesWithText("Subscribe to 1 podcast").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText("Subscribe to 1 podcast").assertIsDisplayed()
                onNodeWithText("Finish importing 1 podcast").assertDoesNotExist()
                assertThat(runBlocking { graph.importRepository.observeOpenSessions().first() }).hasSize(2)
            } finally {
                source.delete()
            }
        }

    /**
     * 11's next-start crash offer through the real window content and graph (D62/PO-10): the
     * dialog appears once the database gate is open, newest file first, and every button goes
     * through the offer's injected OS seams — "Don't send" marks `.dismissed`, "Show report"
     * reveals without marking, "Send by email" opens the bounded `mailto:` and marks `.sent`.
     */
    @Test
    fun unsentCrashFilesOfferTheEmailDialogThroughTheWindow() =
        runComposeUiTest {
            val crashDirs = tempAppDirs("nd-window-crash").also { Files.createDirectories(it.state) }
            val newest = crashDirs.writeCrashFile("crash-20261107-120000.txt", "newest")
            val older = crashDirs.writeCrashFile("crash-20261106-120000.txt", "older")
            Files.setLastModifiedTime(newest, FileTime.fromMillis(DesktopClock.now()))
            Files.setLastModifiedTime(older, FileTime.fromMillis(DesktopClock.now() - 60_000))
            val openedMailtos = mutableListOf<String>()
            val revealedPaths = mutableListOf<String>()
            setWindowContent(
                crashOffer =
                    DesktopCrashOffer(
                        dirs = crashDirs,
                        buildInfo = crashDialogBuildInfo(),
                        clock = DesktopClock,
                        crashReporter = FakeCrashReporter(true),
                        urls =
                            ExternalUrlOpener {
                                openedMailtos += it
                                OpenResult.OPENED
                            },
                        reveal =
                            RevealInFolder {
                                revealedPaths += it
                                true
                            },
                    ),
            )

            waitUntil("the crash dialog shows", TIMEOUT_MS) {
                onAllNodesWithText("Send by email").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Don't send").performClick()
            waitUntil("the newer file is dismissed", TIMEOUT_MS) {
                Files.exists(crashDirs.state.resolve("crash-20261107-120000.dismissed"))
            }
            onNodeWithText("Show report").performClick()
            waitUntil("the reveal seam is called", TIMEOUT_MS) { revealedPaths.isNotEmpty() }
            assertThat(revealedPaths.single()).isEqualTo(older.toString())
            assertThat(Files.exists(older)).isTrue()
            onNodeWithText("Send by email").performClick()
            waitUntil("the mailto seam is called", TIMEOUT_MS) { openedMailtos.isNotEmpty() }
            assertThat(openedMailtos.single()).startsWith("mailto:neutrodyne@example.com")
            waitUntil("the older file is sent", TIMEOUT_MS) {
                Files.exists(crashDirs.state.resolve("crash-20261106-120000.sent"))
            }
            waitUntil("all crashes handled closes the dialog", TIMEOUT_MS) {
                onAllNodesWithText("Send by email").fetchSemanticsNodes().isEmpty()
            }
        }

    @Test
    fun reconnectOpensTheRealSetupEntryWithTheSavedAddress() =
        runComposeUiTest {
            val sync = FakeSyncController()
            sync.status.value = SyncStatus.Reconnect(RECONNECT_URL, "anna")
            setWindowContent(sync = sync)
            onNodeWithTag("radio_menu").performClick()
            onNodeWithTag("nav_library").performClick()
            waitUntil("the reconnect banner shows", TIMEOUT_MS) {
                onAllNodesWithText(RECONNECT_MESSAGE).fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("Reconnect").performClick()
            waitUntil("the real setup entry opens", TIMEOUT_MS) {
                onAllNodesWithText("Link a device").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(RECONNECT_URL).assertExists()
            onNodeWithText(RECONNECT_MESSAGE).assertDoesNotExist()
        }

    @Test
    fun notNowSurvivesWindowContentRecreationThroughTheAppScopedHolder() =
        runComposeUiTest {
            val sync = FakeSyncController()
            sync.status.value = SyncStatus.Reconnect(RECONNECT_URL, "anna")
            val generation = mutableIntStateOf(0)
            setWindowContent(sync = sync, generation = { generation.intValue })
            onNodeWithTag("radio_menu").performClick()
            onNodeWithTag("nav_library").performClick()
            waitUntil("the reconnect banner shows", TIMEOUT_MS) {
                onAllNodesWithText(RECONNECT_MESSAGE).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Not now").performClick()
            assertThat(graph.reconnectDismissals.isDismissed(ReconnectProfile(RECONNECT_URL, "anna"))).isTrue()

            runOnIdle { generation.intValue++ }
            onNodeWithTag("radio_menu").performClick()
            onNodeWithTag("nav_library").performClick()
            onNodeWithText(RECONNECT_MESSAGE).assertDoesNotExist()
            assertThat(sync.status.value).isEqualTo(SyncStatus.Reconnect(RECONNECT_URL, "anna"))
        }

    private fun ComposeUiTest.setWindowContent(
        crashOffer: DesktopCrashOffer? = null,
        sync: FakeSyncController? = null,
        generation: () -> Int = { 0 },
    ) {
        // The default (Feeds) tab is VM-backed: its repositories come from the graph's database
        // accessor, which gates on the open — idempotent, so per-test awaits stay harmless.
        runBlocking { graph.databaseOpener.awaitOpen() }
        setContent {
            key(generation()) {
                NeutrodyneWindowContent(
                    installers = graph.entryInstallers,
                    menuActions = DesktopMenuActions(),
                    viewModelFactory = graph.metroViewModelFactory,
                    playbackController = graph.playbackController,
                    playbackState = graph.playbackStateSource,
                    updateChecker = graph.appUpdateChecker,
                    updateNotices = graph.updateNotices,
                    settings = graph.settingsRepository,
                    crashOffer = crashOffer,
                    syncController = sync?.let { { it } },
                    reconnectDismissals = { graph.reconnectDismissals },
                )
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val RECONNECT_URL = "https://sync.example.org/personal"
        const val RECONNECT_MESSAGE = "Reconnect to sync.example.org to keep syncing"

        val UPDATE_INFO =
            UpdateInfo(
                "1.0.0",
                1000095L,
                26,
                "2026-10-19T00:00:00Z",
                "New version",
                "https://github.com/L-K-M/Neutrodyne/releases/tag/v1.0.0",
                null,
                null,
                null,
            )

        val IMPORT_NOTIFICATION =
            DesktopNotification(
                id = "import-7",
                kind = NotificationKind.IMPORT_BACKUP,
                title = "Import finished",
                body = "3 added",
                route = "neutrodyne://open/import/7",
            )

        val OFFER =
            RemoteSessionOffer(
                episodeId = 42,
                title = "Episode 7",
                podcastTitle = "Show",
                positionMs = 1_394_000,
                deviceName = "Pixel 8",
            )
    }
}

/** The key the platform-kind probe installs under — never a production route. */
private object PlatformProbeKey : NavKey

/** Captures `LocalPlatformKind` once the probe entry composes inside the window's locals. */
private fun platformProbeInstaller(sink: (PlatformKind) -> Unit): EntryProviderInstaller =
    {
        entry<PlatformProbeKey> { sink(LocalPlatformKind.current) }
    }
