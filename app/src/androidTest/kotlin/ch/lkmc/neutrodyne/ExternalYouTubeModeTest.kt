// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.app.Application
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.printToLog
import androidx.lifecycle.SavedStateHandle
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import androidx.work.workDataOf
import ch.lkmc.neutrodyne.acceptance.RecordedYouTubeTraffic
import ch.lkmc.neutrodyne.acceptance.assertInitialExternalChannel
import ch.lkmc.neutrodyne.acceptance.assertMixedGroupSkipsExternal
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.data.importing.ImportFetchWorker
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshScheduler
import ch.lkmc.neutrodyne.core.data.refresh.RefreshWorker
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.ContextEligibility
import ch.lkmc.neutrodyne.core.domain.RefreshController
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.YouTubeSettingKeys
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.Route
import ch.lkmc.neutrodyne.core.network.okhttp.DebugInterceptors
import ch.lkmc.neutrodyne.core.testing.FakeEngineSettings
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.ReleaseSmoke
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
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
import ch.lkmc.neutrodyne.di.AndroidAppGraph
import ch.lkmc.neutrodyne.youtube.engine.EngineSettings
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createDynamicGraphFactory
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** E8 with the application graph. Browser handoff is captured, never sent to an installed app. */
@OptIn(ExperimentalTestApi::class)
@ReleaseSmoke
@RunWith(AndroidJUnit4::class)
class ExternalYouTubeModeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    // MainActivity exists in every variant; ui-test-manifest's empty activity is debug-only.
    private fun showJourney(content: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent(content = content) }
    }

    // JUnit4 invalidates the whole class when any test method is not void; the device runner
    // then reports only an initializationError that the result XML drops (2026-11-04). The
    // explicit Unit keeps that a compile-time property: `with` would otherwise infer the last
    // expression's type, and assertExists() returns SemanticsNodeInteraction.
    @Test fun recordedSharedHandleAndImportedChannelsStayExternal(): Unit =
        with(compose) {
            val previous = Locale.getDefault()
            Locale.setDefault(Locale.ENGLISH)
            installFakeImageLoader()
            val application =
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NeutrodyneApplication
            val db = TestDb.inMemory(application, driver = BundledSQLiteDriver())
            val clock = TestClock()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val replay =
                RecordedYouTubeTraffic { path ->
                    InstrumentationRegistry
                        .getInstrumentation()
                        .context.assets
                        .open(path)
                        .bufferedReader()
                        .use { it.readText() }
                }
            val settings = FakeSettingsRepository()
            runBlocking {
                settings.set(DownloadSettingKeys.AUTO_DOWNLOAD, true)
                settings.set(YouTubeSettingKeys.ENGINE_ENABLED, false)
            }
            val graph =
                createDynamicGraphFactory<AndroidAppGraph.Factory>(
                    RecordedJourneyBindings(db, clock, scope, replay, settings),
                ).create(application)
            val group = runBlocking { db.groupDao().insert(groupEntity(name = "Journey group", orderKey = "a0")) }
            val routing = MainIntentViewModel(SavedStateHandle())
            routing.receive(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "@mkbhd"),
                application.packageName,
            )
            var showing by mutableStateOf(true)
            try {
                showJourney {
                    if (showing) {
                        CompositionLocalProvider(
                            LocalMetroViewModelFactory provides graph.metroViewModelFactory,
                            LocalPlatformKind provides PlatformKind.ANDROID,
                            LocalUiClock provides clock,
                            LocalPlatformActions provides CapturedBrowserActions(),
                        ) {
                            NeutrodyneRoot(
                                RootUiState.READY,
                                RootActions({}, {}, {}, {}, { false }),
                                RootSlots(player = {}, userMessages = emptyFlow()),
                                graph.entryInstallers,
                                routes = routing.routes,
                                platform = BuildInfo.Platform.ANDROID,
                            )
                        }
                    }
                }
                // The channel resolver's own 20-second budget targets real hardware (04's
                // resolution budgets, PB18); a cold interpreter-mode API 26 CI emulator can burn
                // it on first-ever class loading. The sheet's Retry affordance recovers exactly
                // as a user would; every further assertion still proves the engine-off journey.
                waitUntil(timeoutMillis = PREVIEW_RECOVERY_TIMEOUT_MS) {
                    val resolved = onAllNodesWithText("MKBHD & Studio").fetchSemanticsNodes().isNotEmpty()
                    if (!resolved && onAllNodesWithText(NETWORK_FAILURE_TEXT).fetchSemanticsNodes().isNotEmpty()) {
                        onNodeWithText(RETRY_LABEL).performClick()
                    }
                    resolved
                }
                assertEquals(emptyList(), runBlocking { db.podcastDao().allForExport() })
                val checkbox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
                onNode(hasText("Journey group") and checkbox).performClick()
                onNodeWithText("Subscribe").performClick()
                awaitJourney(SUBSCRIBE_TIMEOUT_MS) { runBlocking { db.podcastDao().allForExport().isNotEmpty() } }
                runBlocking {
                    runJourneyWorker(
                        application,
                        graph,
                        RefreshWorker::class.java.name,
                        workDataOf("scope" to "all", "origin" to "SUBSCRIBE"),
                    )
                }
                awaitJourney(REFRESH_TIMEOUT_MS) {
                    runBlocking {
                        db
                            .podcastDao()
                            .allForExport()
                            .singleOrNull()
                            ?.initialFetch == false
                    }
                }
                val channel = runBlocking { db.podcastDao().allForExport().single() }
                runBlocking { assertInitialExternalChannel(db, channel.id, group) }
                // Drive the graph-created import worker after durable confirmation.
                val session =
                    runBlocking {
                        assertIs<Outcome.Success<Long>>(
                            graph.importRepository.create(
                                ImportSource(
                                    "https://fixtures.neutrodyne.invalid/subscriptions.json",
                                    "libretube.json",
                                ),
                            ),
                        ).value
                    }
                runBlocking { assertIs<Outcome.Success<Unit>>(graph.importRepository.confirm(session)) }
                runBlocking {
                    runJourneyWorker(
                        application,
                        graph,
                        ImportFetchWorker::class.java.name,
                        workDataOf("sessionId" to session),
                    )
                }
                awaitJourney(REFRESH_TIMEOUT_MS) {
                    runBlocking {
                        val channels = db.podcastDao().allForExport()
                        channels.size == 2 && channels.none { it.initialFetch }
                    }
                }
                runBlocking {
                    val importedId = RecordedYouTubeTraffic.IMPORTED
                    val imported = db.podcastDao().allForExport().single { it.youtubeChannelId == importedId }
                    val membership = db.groupDao().membershipPairs().single { it.podcastId == imported.id }
                    assertEquals("Imported group", db.groupDao().groupById(membership.groupId)?.name)
                    assertInitialExternalChannel(db, imported.id, membership.groupId)
                    val id = checkNotNull(db.episodeDao().byIdentityKey(channel.id, "g:yt:video:aaaaaaaaaaa")).id
                    graph.queueRepository().addNext(listOf(id))
                    graph.downloadController().request(listOf(id), DownloadLane.MANUAL)
                    assertEquals(emptyList(), db.queueDao().entries())
                    assertEquals(emptyList(), db.downloadDao().allRows())
                }
                assertEquals(
                    ExternalReason.DISABLED_BY_USER,
                    graph.youtubeCapabilities.capabilities.value.externalReason,
                )
                // Fail both subscription variants/fallbacks and the real monitor's controls.
                replay.feedsUnavailable = true
                runBlocking {
                    runJourneyWorker(
                        application,
                        graph,
                        RefreshWorker::class.java.name,
                        workDataOf("scope" to "all", "origin" to "MANUAL", "force" to true),
                    )
                }
                val outage =
                    "YouTube feeds aren't responding. " +
                        "Your channels will update automatically when YouTube is back."
                waitUntil(20_000) { onAllNodesWithText(outage).fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithText(outage).assertCountEquals(1)
                runBlocking {
                    assertEquals(2, db.podcastDao().allForExport().size)
                    assertEquals(emptyList(), db.downloadDao().allRows())
                    assertMixedGroupSkipsExternal(db, graph.playContextResolver(), group)
                }
                assertTrue(
                    replay.requests.any {
                        it.url.queryParameter("channel_id") == "UCBR8-60-B28hp2BmDPdntcQ"
                    },
                )
                replay.assertPageAndFeedEvidence()
                // Adding a handle is never a directory query for the handle itself.
                assertFalse(
                    replay.requests.any {
                        it.url.queryParameter("term") == "@mkbhd" || it.url.queryParameter("title") == "@mkbhd"
                    },
                )
            } finally {
                runOnIdle { showing = false }
                waitForIdle()
                scope.cancel()
                db.close()
                Locale.setDefault(previous)
            }
        }

    @Test fun browserFailureSuccessfulOpenUndoAndSettings(): Unit =
        with(compose) {
            val previous = Locale.getDefault()
            Locale.setDefault(Locale.ENGLISH)
            installFakeImageLoader()
            val application =
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NeutrodyneApplication
            val db = TestDb.inMemory(application, driver = BundledSQLiteDriver())
            val clock = TestClock()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val bindings = ExternalModeBindings(db, clock, scope)
            val graph = createDynamicGraphFactory<AndroidAppGraph.Factory>(bindings).create(application)
            val actions = CapturedBrowserActions()
            val id =
                runBlocking {
                    val channel =
                        db.podcastDao().insertPodcast(
                            podcastEntity {
                                copy(sourceType = SourceType.YOUTUBE_CHANNEL, initialFetch = false)
                            },
                        )
                    db.episodeDao().insertStub(
                        episodeEntity(podcastId = channel, title = "External clip") {
                            copy(externalMediaId = "dQw4w9WgXcQ")
                        },
                    )
                }
            var showing by mutableStateOf(true)
            try {
                showJourney {
                    if (showing) {
                        CompositionLocalProvider(
                            LocalMetroViewModelFactory provides graph.metroViewModelFactory,
                            LocalPlatformKind provides PlatformKind.ANDROID,
                            LocalUiClock provides clock,
                            LocalPlatformActions provides actions,
                        ) {
                            NeutrodyneRoot(
                                RootUiState.READY,
                                RootActions({}, {}, {}, {}, { false }),
                                RootSlots(player = {}, userMessages = emptyFlow()),
                                graph.entryInstallers,
                                platform = BuildInfo.Platform.ANDROID,
                            )
                        }
                    }
                }
                waitUntil(
                    timeoutMillis = 10_000,
                ) { onAllNodesWithText("External clip").fetchSemanticsNodes().isNotEmpty() }
                val labels =
                    onNodeWithText("External clip")
                        .fetchSemanticsNode()
                        .config[SemanticsActions.CustomActions]
                        .map { it.label }
                assertFalse("Play next" in labels)
                assertFalse("Download" in labels)
                onNodeWithText("External clip").performCustomAccessibilityActionWithLabel("Watch on YouTube")
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithText("No app can open this link").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText("No app can open this link").assertExists()
                assertNull(runBlocking { db.episodeStateDao().byEpisode(id)?.playedAt })
                onNodeWithText("Copy link").performClick()
                runOnIdle { actions.result = OpenResult.OPENED }
                onNodeWithText("External clip").performCustomAccessibilityActionWithLabel("Watch on YouTube")
                waitUntil(
                    timeoutMillis = 10_000,
                ) { runBlocking { db.episodeStateDao().byEpisode(id)?.playedAt != null } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Undo").performClick()
                waitUntil(
                    timeoutMillis = 10_000,
                ) { runBlocking { db.episodeStateDao().byEpisode(id)?.playedAt == null } }
                assertNull(runBlocking { db.downloadDao().byEpisode(id) })
                assertEquals(emptyList(), runBlocking { db.queueDao().entries() })
                assertEquals(List(2) { "https://www.youtube.com/watch?v=dQw4w9WgXcQ" }, actions.opened)
                val outage =
                    "YouTube feeds aren't responding. " +
                        "Your channels will update automatically when YouTube is back."
                runOnIdle {
                    bindings.health.state.value =
                        ch.lkmc.neutrodyne.youtube.api
                            .YouTubeHealthState(feedOutageLevel = 1)
                }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(outage).fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithText(outage).assertCountEquals(1)
                onNodeWithText("Retry now").performClick()
                assertEquals(1, bindings.health.retries)
                onNodeWithTag("nav_library").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(outage).fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithText(outage).assertCountEquals(1)
                onAllNodesWithContentDescription("Settings").onFirst().performClick()
                onNodeWithText("YouTube").performClick()
                onNodeWithText("YouTube engine turned off").assertExists()
                onNodeWithText("Play YouTube in the app").assertExists()
            } finally {
                runOnIdle { showing = false }
                waitForIdle()
                scope.cancel()
                db.close()
                Locale.setDefault(previous)
            }
        }

    private companion object {
        const val PREVIEW_RECOVERY_TIMEOUT_MS = 90_000L
        const val SUBSCRIBE_TIMEOUT_MS = 20_000L
        const val REFRESH_TIMEOUT_MS = 30_000L
        const val OUTAGE_TIMEOUT_MS = 20_000L

        // Locale.ENGLISH is forced above, so the EN sheet copy (yt_network_error, action_retry)
        // is exact — including the typographic apostrophe.
        const val NETWORK_FAILURE_TEXT = "Couldn’t reach YouTube. Check your connection and try again."
        const val RETRY_LABEL = "Retry"
    }

    /**
     * A timed-out wait must say which step the journey was in: the per-test logcat upload carries
     * the whole semantics tree, so a CI-only stall is diagnosable from the artifact. MainActivity
     * keeps its own composition under the journey's setContent, so the activity hosts two roots;
     * each subtree is printed (SmokeTest's SettingsTouchTargets precedent).
     */
    private fun awaitJourney(
        timeoutMillis: Long,
        condition: () -> Boolean,
    ) {
        try {
            compose.waitUntil(timeoutMillis = timeoutMillis, condition = condition)
        } catch (failure: ComposeTimeoutException) {
            runCatching {
                val roots = compose.onAllNodes(isRoot())
                repeat(roots.fetchSemanticsNodes().size) { index -> roots[index].printToLog("E8Journey") }
            }
            throw failure
        }
    }
}

@BindingContainer
private class ExternalModeBindings(
    private val db: NeutrodyneDatabase,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    val health =
        ch.lkmc.neutrodyne.core.testing
            .FakeYouTubeHealth()

    @Provides fun health(): ch.lkmc.neutrodyne.youtube.api.YouTubeHealth = health

    @Provides fun database(): NeutrodyneDatabase = db

    @Provides fun clock(): Clock = clock

    @Provides @ApplicationScope
    fun scope(): CoroutineScope = scope

    @Provides fun settings(): SettingsRepository =
        FakeSettingsRepository().apply {
            runBlocking { set(YouTubeSettingKeys.ENGINE_ENABLED, false) }
        }

    @Provides fun refresh(): RefreshController = FakeRefreshController()

    @Provides fun engineSettings(): EngineSettings =
        FakeEngineSettings().apply {
            state.value = state.value.copy(enabled = false)
        }

    @Provides fun network(): NetworkMonitor = FakeNetworkMonitor(FakeNetworkMonitor.OFFLINE)
}

private class CapturedBrowserActions : PlatformActions {
    var result = OpenResult.NO_HANDLER
    val opened = mutableListOf<String>()
    override val urls =
        ExternalUrlOpener {
            opened += it
            result
        }
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

@BindingContainer
private class RecordedJourneyBindings(
    private val db: NeutrodyneDatabase,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val replay: Interceptor,
    private val settings: SettingsRepository,
) {
    // INITIAL work is driven explicitly through the real graph worker factory below.
    @Provides fun scheduler(): RefreshScheduler =
        object : RefreshScheduler {
            override fun enqueueNow(
                scope: RefreshScope,
                force: Boolean,
                pagesOnly: Boolean,
                origin: RefreshOrigin,
            ) = Unit

            override suspend fun reschedulePeriodic() = Unit

            override suspend fun enqueueContinuation() = true

            override fun requestFirstFetch() = Unit
        }

    @Provides fun database(): NeutrodyneDatabase = db

    @Provides fun clock(): Clock = clock

    @Provides @ApplicationScope
    fun scope(): CoroutineScope = scope

    @Provides fun settings(): SettingsRepository = settings

    @Provides fun engineSettings(): EngineSettings =
        FakeEngineSettings().apply {
            state.value = state.value.copy(enabled = false)
        }

    @Provides fun network(): NetworkMonitor = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)

    @Provides @DebugInterceptors
    fun interceptors(): Set<Interceptor> = setOf(replay)
}

/** Direct worker execution tests ingestion, not WorkManager timing or device lifecycle. */
private suspend fun runJourneyWorker(
    application: Application,
    graph: AndroidAppGraph,
    name: String,
    input: Data,
) {
    val executor = Executor { it.run() }
    val parameters =
        WorkerParameters(
            UUID.randomUUID(),
            input,
            emptyList(),
            WorkerParameters.RuntimeExtras(),
            0,
            0,
            executor,
            Dispatchers.Default,
            WorkManagerTaskExecutor(executor),
            graph.workerFactory,
            { _, _, _ -> error("Journey worker must not publish progress") },
            { _, _, _ -> error("Journey worker must not start a foreground service") },
        )
    val worker = graph.workerFactory.createWorker(application, name, parameters) as CoroutineWorker
    assertEquals(
        androidx.work.ListenableWorker.Result
            .success(),
        worker.doWork(),
    )
}
