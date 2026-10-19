// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
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
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.Route
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.di.createDesktopGraph
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.notifications.DesktopBannerNotifier
import ch.lkmc.neutrodyne.desktop.platform.DesktopClock
import ch.lkmc.neutrodyne.desktop.shell.tempAppDirs
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
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
        Locale.setDefault(previousLocale)
    }

    @Test
    fun fiveDestinationsAreLabelled() =
        runComposeUiTest {
            setWindowContent()

            for (tag in listOf("nav_feeds", "nav_library", "nav_up_next", "nav_downloads", "nav_discover")) {
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
            onAllNodesWithContentDescription("Settings").onFirst().performClick()
            onNodeWithText("Appearance").assertExists()

            onAllNodesWithText("About").onFirst().performClick()
            onNodeWithText("Neutrodyne").assertExists()
            onNodeWithText("Debug build").assertExists()
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

    @Test
    fun licencesListTheOpenJdkRuntime() =
        runComposeUiTest {
            setWindowContent()

            onAllNodesWithContentDescription("Settings").onFirst().performClick()
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

    private fun ComposeUiTest.setWindowContent() {
        // The default (Feeds) tab is VM-backed: its repositories come from the graph's database
        // accessor, which gates on the open — idempotent, so per-test awaits stay harmless.
        runBlocking { graph.databaseOpener.awaitOpen() }
        setContent {
            NeutrodyneWindowContent(
                installers = graph.entryInstallers,
                menuActions = DesktopMenuActions(),
                viewModelFactory = graph.metroViewModelFactory,
            )
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L

        val IMPORT_NOTIFICATION =
            DesktopNotification(
                id = "import-7",
                kind = NotificationKind.IMPORT_BACKUP,
                title = "Import finished",
                body = "3 added",
                route = "neutrodyne://open/import/7",
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
