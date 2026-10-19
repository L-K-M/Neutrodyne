// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.di

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.RefreshController
import ch.lkmc.neutrodyne.core.domain.SearchRepository
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DirectoryHit
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.ProviderId
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.core.model.SearchResults
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.DirectoryKey
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.PodcastPreviewKey
import ch.lkmc.neutrodyne.core.navigation.Route
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSearchRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.FilePicker
import ch.lkmc.neutrodyne.core.ui.platform.FileSaver
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.platform.PlatformActions
import ch.lkmc.neutrodyne.core.ui.root.NeutrodyneRoot
import ch.lkmc.neutrodyne.core.ui.root.RootActions
import ch.lkmc.neutrodyne.core.ui.root.RootSlots
import ch.lkmc.neutrodyne.core.ui.root.RootUiState
import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.shell.tempAppDirs
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createDynamicGraphFactory
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Production entries and subscriptions over TestDb and recorded HTTP, without live directories. */
@OptIn(ExperimentalTestApi::class)
class DiscoveryJourneyTest {
    @Test fun previewConfirmationWritesEpisodesAndMembership() = journey(Entry.PREVIEW)

    @Test fun addConfirmationWritesEpisodesAndMembership() = journey(Entry.ADD)

    private fun journey(entry: Entry) =
        runComposeUiTest {
            val previous = Locale.getDefault()
            Locale.setDefault(Locale.ENGLISH)
            installFakeImageLoader()
            val clock = TestClock()
            val db = TestDb.inMemory(clock = clock)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val dirs = tempAppDirs("m7-journey").also { it.ensureCreated() }
            val build = BuildInfoLoader.load()
            val reporter = DesktopCrashReporter(dirs, build, clock, RecentLogBuffer())
            val search = FakeSearchRepository()
            val graph =
                createDynamicGraphFactory<M2JourneyGraph.Factory>(DiscoveryJourneyBindings(db, clock, scope, search))
                    .create(dirs, build, reporter)
            var showing by mutableStateOf(true)
            try {
                MockWebServer().use { server ->
                    val feed = File("../feeds/src/test/resources/discovery/journey-feed.xml").readText()
                    server.enqueue(
                        MockResponse
                            .Builder()
                            .code(200)
                            .addHeader("Content-Type", "application/rss+xml")
                            .body(feed)
                            .build(),
                    )
                    server.start()
                    val url = server.url("/feed.xml").toString()
                    val groupId =
                        runBlocking {
                            (
                                graph.groups.create(
                                    GroupDraft("Journey group"),
                                ) as ch.lkmc.neutrodyne.core.common.Outcome.Success
                            ).value
                        }
                    search.searchAnswer = { query ->
                        flowOf(
                            SearchResults(
                                query,
                                persistentListOf(
                                    DirectoryHit(url, "Directory show", providers = persistentSetOf(ProviderId.APPLE)),
                                ),
                                persistentMapOf(ProviderId.APPLE to ProviderStatus.Ok),
                                true,
                            ),
                        )
                    }
                    val route =
                        if (entry == Entry.ADD) {
                            Route.Push(AddPodcastKey(url))
                        } else {
                            Route.Navigate(DiscoverKey, listOf(DirectoryKey("history", null)))
                        }
                    setContent {
                        if (showing) {
                            CompositionLocalProvider(
                                LocalMetroViewModelFactory provides graph.metroViewModelFactory,
                                LocalUiClock provides clock,
                                LocalPlatformKind provides PlatformKind.DESKTOP,
                                LocalPlatformActions provides DiscoveryJourneyPlatformActions,
                            ) {
                                NeutrodyneRoot(
                                    state = RootUiState.READY,
                                    actions = RootActions({}, {}, {}, {}, { false }),
                                    slots = RootSlots(player = {}, userMessages = emptyFlow()),
                                    installers = graph.entryInstallers,
                                    platform = BuildInfo.Platform.DESKTOP,
                                    routes = flowOf(route),
                                )
                            }
                        }
                    }
                    if (entry == Entry.PREVIEW) {
                        waitUntil(timeoutMillis = 10_000) {
                            onAllNodes(
                                androidx.compose.ui.test
                                    .hasText("Directory show"),
                            ).fetchSemanticsNodes().isNotEmpty()
                        }
                        onNodeWithText("Directory show").performClick()
                    }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodes(
                            androidx.compose.ui.test
                                .hasText("M7 Subscribe Journey"),
                        ).fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithText("M7 Subscribe Journey").assertIsDisplayed()
                    assertTrue(runBlocking { db.podcastDao().allForExport().isEmpty() })
                    onNode(
                        hasText("Journey group") and
                            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox),
                    ).performClick()
                    if (entry == Entry.PREVIEW) {
                        clock.advanceBy(16.minutes)
                        server.enqueue(
                            MockResponse
                                .Builder()
                                .code(200)
                                .body(feed)
                                .build(),
                        )
                    }
                    onNodeWithText("Subscribe").performClick()
                    waitUntil(timeoutMillis = 10_000) { runBlocking { db.podcastDao().allForExport().isNotEmpty() } }
                    val row = runBlocking { db.podcastDao().allForExport().single() }
                    assertEquals(1, runBlocking { db.podcastDao().episodeCount(row.id) })
                    assertEquals(if (entry == Entry.PREVIEW) 2 else 1, server.requestCount)
                    assertTrue(
                        runBlocking {
                            db.groupDao().membershipPairs().any {
                                it.podcastId == row.id &&
                                    it.groupId == groupId
                            }
                        },
                    )
                    if (entry == Entry.PREVIEW) {
                        onNodeWithTag("nav_discover").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                        repeat(2) {
                            onRoot().performKeyInput { pressKey(Key.Escape) }
                            waitForIdle()
                        }
                        onNodeWithText("history").assertDoesNotExist()
                        onNodeWithTag("nav_discover").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                        onRoot().performKeyInput {
                            keyDown(Key.CtrlLeft)
                            pressKey(Key.F)
                            keyUp(Key.CtrlLeft)
                        }
                        onNode(hasSetTextAction()).assertIsFocused()
                        onRoot().performKeyInput { pressKey(Key.Tab) }
                        onNodeWithText("Add by URL").assertIsFocused()
                    }
                }
            } finally {
                runOnIdle { showing = false }
                waitForIdle()
                scope.cancel()
                db.close()
                dirs.data.parent
                    .toFile()
                    .deleteRecursively()
                Locale.setDefault(previous)
            }
        }

    private enum class Entry { ADD, PREVIEW }
}

@BindingContainer
private class DiscoveryJourneyBindings(
    private val db: NeutrodyneDatabase,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val search: SearchRepository,
) {
    @Provides fun search(): SearchRepository = search

    @Provides fun database(): NeutrodyneDatabase = db

    @Provides fun clock(): Clock = clock

    @Provides @ApplicationScope
    fun scope(): CoroutineScope = scope

    @Provides fun settings(): SettingsRepository = FakeSettingsRepository()

    @Provides fun refresh(): RefreshController = FakeRefreshController()

    @Provides fun network(): NetworkMonitor = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
}

private val DiscoveryJourneyPlatformActions =
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
