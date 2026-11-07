// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.DesktopNotification
import ch.lkmc.neutrodyne.core.common.NotificationKind
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.database.SyncHeldEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.model.settings.SyncSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.UpdateSettingKeys
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckState
import ch.lkmc.neutrodyne.core.model.update.UpdateInfo
import ch.lkmc.neutrodyne.core.model.update.UpdateNotice
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.Route
import ch.lkmc.neutrodyne.core.testing.FakeAppUpdateChecker
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeUpdateNotices
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.root.StartupGateState
import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.di.createDesktopGraph
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.notifications.DesktopBannerNotifier
import ch.lkmc.neutrodyne.desktop.platform.DesktopClock
import ch.lkmc.neutrodyne.desktop.shell.tempAppDirs
import ch.lkmc.neutrodyne.sync.api.SyncController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        graph.appScope.cancel()
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
            onAllNodesWithContentDescription("Update available").onFirst().assertExists()
            onNodeWithText("Turn off").performClick()
            waitUntil("the disclosure is durably dismissed", TIMEOUT_MS) { notices.pending.value == null }
            assertThat(runBlocking { settings.get(UpdateSettingKeys.CHECK_ENABLED) }).isFalse()
            runOnIdle { checker.state.value = UpdateCheckState.Idle(0) }
            onAllNodesWithContentDescription("Update available").assertCountEquals(0)
            onNodeWithText("Turn off").assertDoesNotExist()
        }

    @Test
    fun updateSettingsAndBundledHelpOpenThroughTheProductionGraph() =
        runComposeUiTest {
            setWindowContent()
            onAllNodesWithContentDescription("Settings").onFirst().performClick()
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
     * The production window content maps the real controller's persisted held batch onto the
     * root banner (08 priority 3), and the banner's Review opens the production held-changes
     * dialog through the graph's real entry installers.
     */
    @Test
    fun aPersistedHeldBatchOpensTheProductionReviewDialog() =
        runComposeUiTest {
            runBlocking { graph.databaseOpener.awaitOpen() }
            seedLinkedAndHeld(graph)
            setHeldContent()

            waitUntil("the held-changes banner loads", TIMEOUT_MS) {
                onAllNodesWithText("Another device removed 2 podcasts and 1 group. Apply here too?")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            onNodeWithText("Review").performClick()

            waitUntil("the held-changes dialog opens", TIMEOUT_MS) {
                onAllNodesWithText("Apply this on this device too?").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Another device removed 2 podcasts and 1 group").assertIsDisplayed()
            onNodeWithText("Held Show A").assertIsDisplayed()
            onNodeWithText("Held Show B").assertIsDisplayed()
            onNodeWithText("Held Group").assertIsDisplayed()
            onNodeWithText("Apply").assertIsDisplayed()
            onNodeWithText("Keep mine").assertIsDisplayed()
            onNodeWithText("Decide later").assertIsDisplayed()
        }

    /**
     * Apply through the production dialog runs the real resolution end to end: the batch's
     * podcast and group are deleted and the row closes, while a second held batch stays
     * offered on the banner (10 Mass-change guard step 3).
     */
    @Test
    fun applyingThroughTheHeldDialogRemovesOnlyTheSelectedBatch() =
        runComposeUiTest {
            runBlocking { graph.databaseOpener.awaitOpen() }
            val db = graph.databaseOpener.requireDatabase()
            seedLinked(graph)
            db.withWriteTransaction {
                db.podcastDao().insertPodcast(podcastEntity(syncId = "p-1", title = "First show"))
                db.groupDao().insert(groupEntity(uuid = GROUP_UUID, name = "First group", orderKey = "a0"))
                db.podcastDao().insertPodcast(podcastEntity(syncId = "p-2", title = "Second show"))
            }
            seedHeldRow(
                db,
                listOf("p-1"),
                listOf(GROUP_UUID),
                listOf("First show"),
                listOf("First group"),
                heldAt = 1,
            )
            seedHeldRow(db, listOf("p-2"), emptyList(), listOf("Second show"), emptyList(), heldAt = 2)
            setHeldContent()

            waitUntil("the oldest batch's banner loads", TIMEOUT_MS) {
                onAllNodesWithText("Another device removed 1 podcast and 1 group. Apply here too?")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            onNodeWithText("Review").performClick()
            waitUntil("the held-changes dialog opens", TIMEOUT_MS) {
                onAllNodesWithText("Apply this on this device too?").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Apply").performClick()

            waitUntil("the dialog closes after Apply", TIMEOUT_MS) {
                onAllNodesWithText("Apply this on this device too?").fetchSemanticsNodes().isEmpty()
            }
            assertThat(runBlocking { db.podcastDao().bySyncId("p-1") }).isNull()
            assertThat(runBlocking { db.groupDao().groupByUuid(GROUP_UUID) }).isNull()
            assertThat(runBlocking { db.podcastDao().bySyncId("p-2") }).isNotNull()
            waitUntil("the second batch's banner loads", TIMEOUT_MS) {
                onAllNodesWithText("Another device removed 1 podcast and 0 groups. Apply here too?")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        }

    /** Decide later closes the dialog without a write: the batch stays offered, the library keeps its rows. */
    @Test
    fun decidingLaterKeepsTheBatchOffered() =
        runComposeUiTest {
            runBlocking { graph.databaseOpener.awaitOpen() }
            val db = graph.databaseOpener.requireDatabase()
            seedLinked(graph)
            db.withWriteTransaction {
                db.podcastDao().insertPodcast(podcastEntity(syncId = "p-1", title = "First show"))
            }
            val heldId =
                seedHeldRow(db, listOf("p-1"), emptyList(), listOf("First show"), emptyList(), heldAt = 1)
            setHeldContent()

            waitUntil("the held-changes banner loads", TIMEOUT_MS) {
                onAllNodesWithText("Another device removed 1 podcast and 0 groups. Apply here too?")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            onNodeWithText("Review").performClick()
            waitUntil("the held-changes dialog opens", TIMEOUT_MS) {
                onAllNodesWithText("Apply this on this device too?").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Decide later").performClick()

            waitUntil("the dialog closes after Decide later", TIMEOUT_MS) {
                onAllNodesWithText("Apply this on this device too?").fetchSemanticsNodes().isEmpty()
            }
            assertThat(runBlocking { db.syncHeldDao().byId(heldId) }).isNotNull()
            assertThat(runBlocking { db.podcastDao().bySyncId("p-1") }).isNotNull()
            onNodeWithText("Another device removed 1 podcast and 0 groups. Apply here too?")
                .assertIsDisplayed()
        }

    /** A batch resolved elsewhere drops out of the prompt flow; the open dialog closes itself. */
    @Test
    fun aHeldBatchDecidedElsewhereClosesTheDialog() =
        runComposeUiTest {
            runBlocking { graph.databaseOpener.awaitOpen() }
            val heldId = seedLinkedAndHeld(graph)
            setHeldContent()

            waitUntil("the held-changes banner loads", TIMEOUT_MS) {
                onAllNodesWithText("Another device removed 2 podcasts and 1 group. Apply here too?")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            onNodeWithText("Review").performClick()
            waitUntil("the held-changes dialog opens", TIMEOUT_MS) {
                onAllNodesWithText("Apply this on this device too?").fetchSemanticsNodes().isNotEmpty()
            }

            runBlocking {
                graph.databaseOpener
                    .requireDatabase()
                    .syncHeldDao()
                    .delete(heldId)
            }

            waitUntil("the dialog closes", TIMEOUT_MS) {
                onAllNodesWithText("Apply this on this device too?").fetchSemanticsNodes().isEmpty()
            }
        }

    /** The desktop mapping resolves the controller only once the start-up gate reports ready. */
    @Test
    fun theBannerWaitsForTheStartupGateBeforeResolvingTheController() =
        runComposeUiTest {
            runBlocking { graph.databaseOpener.awaitOpen() }
            seedLinkedAndHeld(graph)
            var resolutions = 0
            val provider: () -> SyncController = {
                resolutions++
                graph.syncController
            }
            var startup by mutableStateOf<StartupGateState>(StartupGateState.Pending)
            setContent {
                NeutrodyneWindowContent(
                    installers = graph.entryInstallers,
                    menuActions = DesktopMenuActions(),
                    viewModelFactory = graph.metroViewModelFactory,
                    playbackController = graph.playbackController,
                    playbackState = graph.playbackStateSource,
                    startup = startup,
                    syncController = provider,
                )
            }

            waitForIdle()
            onNodeWithText("Updating your library…").assertExists()
            assertThat(resolutions).isEqualTo(0)

            runOnIdle { startup = StartupGateState.Ready }
            waitUntil("the banner loads after the gate opens", TIMEOUT_MS) {
                onAllNodesWithText("Another device removed 2 podcasts and 1 group. Apply here too?")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            assertThat(resolutions).isEqualTo(1)
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

    private suspend fun seedLinkedAndHeld(graph: ch.lkmc.neutrodyne.desktop.di.DesktopAppGraph): Long {
        seedLinked(graph)
        return seedHeldRow(
            graph.databaseOpener.requireDatabase(),
            podcasts = listOf("a", "b"),
            groups = listOf("news"),
            summaryPodcasts = listOf("Held Show A", "Held Show B"),
            summaryGroups = listOf("Held Group"),
            heldAt = 1,
        )
    }

    private suspend fun seedLinked(graph: ch.lkmc.neutrodyne.desktop.di.DesktopAppGraph) {
        val db = graph.databaseOpener.requireDatabase()
        graph.settingsRepository.set(SyncSettingKeys.SERVER_URL, "https://sync.example")
        graph.secretStore.put("sync:sync.example", "sync", "token".toCharArray())
        db.withWriteTransaction {
            db.syncStateDao().upsert(
                SyncStateEntity(
                    enabled = true,
                    serverUrl = "https://sync.example",
                    accountId = "acc",
                    deviceId = "dev",
                    nodeId = "node",
                    linkedAt = 1L,
                    lastSyncAt = 1L,
                ),
            )
        }
    }

    /** One `sync_held` row in HELD state, as the guard's 10 step 2 write would have left it. */
    private suspend fun seedHeldRow(
        db: ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase,
        podcasts: List<String>,
        groups: List<String>,
        summaryPodcasts: List<String>,
        summaryGroups: List<String>,
        heldAt: Long,
    ): Long {
        val removals = { rids: List<String> ->
            JsonArray(
                rids.map { rid ->
                    buildJsonObject {
                        put("rid", rid)
                        put("clock", "0:0")
                    }
                },
            )
        }
        val batch =
            buildJsonObject {
                put("state", "HELD")
                put("podcasts", removals(podcasts))
                put("groups", removals(groups))
            }.toString()
        val summary =
            buildJsonObject {
                put("podcasts", JsonArray(summaryPodcasts.map(::JsonPrimitive)))
                put("groups", JsonArray(summaryGroups.map(::JsonPrimitive)))
            }.toString()
        return db.syncHeldDao().insert(SyncHeldEntity(batch = batch, summary = summary, heldAt = heldAt))
    }

    /** The window content with the graph's real installers and the lazy held-banner controller. */
    private fun ComposeUiTest.setHeldContent() {
        val provider: () -> SyncController = { graph.syncController }
        setContent {
            NeutrodyneWindowContent(
                installers = graph.entryInstallers,
                menuActions = DesktopMenuActions(),
                viewModelFactory = graph.metroViewModelFactory,
                playbackController = graph.playbackController,
                playbackState = graph.playbackStateSource,
                syncController = provider,
            )
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
                playbackController = graph.playbackController,
                playbackState = graph.playbackStateSource,
                updateChecker = graph.appUpdateChecker,
                updateNotices = graph.updateNotices,
                settings = graph.settingsRepository,
            )
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val GROUP_UUID = "00000000-0000-4000-8000-000000000001"

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
    }
}

/** The key the platform-kind probe installs under — never a production route. */
private object PlatformProbeKey : NavKey

/** Captures `LocalPlatformKind` once the probe entry composes inside the window's locals. */
private fun platformProbeInstaller(sink: (PlatformKind) -> Unit): EntryProviderInstaller =
    {
        entry<PlatformProbeKey> { sink(LocalPlatformKind.current) }
    }
