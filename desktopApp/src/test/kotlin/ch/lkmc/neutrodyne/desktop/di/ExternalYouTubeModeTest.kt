// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.di

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.v2.runComposeUiTest
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.RefreshController
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
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
import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.shell.tempAppDirs
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createDynamicGraphFactory
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Real routes, repositories and external shell bindings; browser handoff is captured, never launched. */
@OptIn(ExperimentalTestApi::class)
class ExternalYouTubeModeTest {
    @Test fun browserFailureSuccessfulOpenUndoAndSettings() =
        runComposeUiTest {
            val previous = Locale.getDefault()
            Locale.setDefault(Locale.ENGLISH)
            installFakeImageLoader()
            val db = TestDb.inMemory()
            val clock = TestClock()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dirs = tempAppDirs().also { it.ensureCreated() }
            val build = BuildInfoLoader.load()
            val reporter = DesktopCrashReporter(dirs, build, clock, RecentLogBuffer())
            val bindings = ExternalModeBindings(db, clock, scope)
            val graph =
                createDynamicGraphFactory<M2JourneyGraph.Factory>(bindings)
                    .create(dirs, build, reporter)
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
                setContent {
                    if (showing) {
                        CompositionLocalProvider(
                            LocalMetroViewModelFactory provides graph.metroViewModelFactory,
                            LocalPlatformKind provides PlatformKind.DESKTOP,
                            LocalUiClock provides clock,
                            LocalPlatformActions provides actions,
                        ) {
                            NeutrodyneRoot(
                                RootUiState.READY,
                                RootActions({}, {}, {}, {}, { false }, {}, {}),
                                RootSlots(player = {}, userMessages = emptyFlow()),
                                graph.entryInstallers,
                                platform = BuildInfo.Platform.DESKTOP,
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
                onNodeWithText("Not yet available on this computer").assertExists()
                onNodeWithText("Play YouTube in the app").assertDoesNotExist()
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

    @Provides fun settings(): SettingsRepository = FakeSettingsRepository()

    @Provides fun refresh(): RefreshController = FakeRefreshController()

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
