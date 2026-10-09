// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.di.createDesktopGraph
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.platform.DesktopClock
import ch.lkmc.neutrodyne.desktop.shell.tempAppDirs
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
                onAllNodesWithText("Your library is empty").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Add a podcast").assertIsDisplayed()
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

    @Test
    fun licencesListTheOpenJdkRuntime() =
        runComposeUiTest {
            setWindowContent()

            onAllNodesWithContentDescription("Settings").onFirst().performClick()
            onAllNodesWithText("Licences").onFirst().performClick()

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

    private fun ComposeUiTest.setWindowContent() {
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
    }
}
