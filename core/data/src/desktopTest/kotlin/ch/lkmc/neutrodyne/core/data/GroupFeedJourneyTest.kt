// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers.Default
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.data.refresh.RefreshControllerImpl
import ch.lkmc.neutrodyne.core.data.repo.PodcastMerger
import ch.lkmc.neutrodyne.core.data.repo.PodcastRepositoryImpl
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.NoGroupChannels
import ch.lkmc.neutrodyne.core.domain.OrderKeys
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.domain.RefreshController
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.FeedPrefs
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import ch.lkmc.neutrodyne.core.network.DesktopNetworkMonitorBindings
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
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
import ch.lkmc.neutrodyne.feeds.jvm.html.JsoupShowNotesSanitizer
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2 AC14: production Feeds/editor/manager routes and ViewModels, with the real group, podcast,
 * feed, episode and live-state repositories over TestDb. Only platform services and portable
 * settings storage are replaced. No production app graph, network or refresh jobs are started.
 */
@OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GroupFeedJourneyTest {
    @Test
    fun createFilterPlayReorderDeleteAndUndo() =
        runComposeUiTest {
            val previousLocale = Locale.getDefault()
            Locale.setDefault(Locale.ENGLISH)
            installFakeImageLoader()
            val fixture = JourneyFixture()
            var showing by mutableStateOf(true)
            try {
                setContent { if (showing) fixture.Content() }
                awaitNode(hasText("Create your first group"))
                onNodeWithText("Create your first group").performClick()
                createGroup("Science", listOf(1, 2))
                val science = fixture.group("Science")
                awaitNode(hasTestTag(science.tabTag()))
                onNodeWithText("Create your first group").assertDoesNotExist()
                assertCount(science, 1)

                onNodeWithTag(science.tabTag()).performClick()
                awaitNode(science.row("Unplayed member"))
                awaitNode(science.row("Played member"))
                onNode(science.row("Outside group")).assertDoesNotExist()
                onNode(science.pageNode("Unplayed")).performClick()
                awaitAbsent(science.row("Played member"))
                onNode(science.row("Unplayed member")).assertIsDisplayed()
                assertCount(science, 1)
                onNode(science.row("Unplayed member") and hasClickAction())
                    .performCustomAccessibilityActionWithLabel("Mark played")
                awaitNode(science.pageNode("You're all caught up"))
                assertCount(science, 0)
                onNode(science.pageNode("Show played episodes")).performClick()
                awaitNode(science.row("Unplayed member"))
                awaitNode(science.row("Played member"))
                assertCount(science, 0)

                openManager()
                onNodeWithText("New group", useUnmergedTree = true).performClick()
                createGroup("News", listOf(3))
                val news = fixture.group("News")
                awaitNode(hasTestTag("groupRow${news.id}"))
                onNodeWithTag("groupRow${news.id}")
                    .performCustomAccessibilityActionWithLabel("Move up")
                waitUntil(timeoutMillis = TIMEOUT_MS) {
                    onNodeWithTag("groupRow${news.id}").fetchSemanticsNode().boundsInRoot.top <
                        onNodeWithTag("groupRow${science.id}").fetchSemanticsNode().boundsInRoot.top
                }
                onNodeWithContentDescription("Back").performClick()
                awaitNode(hasTestTag(news.tabTag()))
                assertTrue(
                    onNodeWithTag(news.tabTag()).fetchSemanticsNode().boundsInRoot.left <
                        onNodeWithTag(science.tabTag()).fetchSemanticsNode().boundsInRoot.left,
                )
                onNodeWithTag(science.tabTag()).assertIsSelected()
                assertCount(news, 1)
                assertEquals(listOf(news.id, science.id), fixture.groups().map { it.id })

                openManager()
                onNodeWithTag("groupMenu${science.id}").performClick()
                onNodeWithText("Delete group").performClick()
                awaitAbsent(hasTestTag("groupRow${science.id}"))
                awaitNode(hasText("Group deleted"))
                onNodeWithText("Undo").performClick()
                awaitNode(hasTestTag("groupRow${science.id}"))
                onNodeWithContentDescription("Back").performClick()
                awaitNode(hasTestTag(science.tabTag()))
                // Deletion falls back to All; restoring a group must not reselect it (08).
                onNodeWithTag("feed-tab:all").assertIsSelected()
                assertCount(science, 0)
                val restored = fixture.group("Science")
                assertEquals(science.id, restored.id)
                assertEquals(science.uuid, restored.uuid)
                assertEquals(
                    setOf(1L, 2L),
                    runBlocking {
                        fixture.graph.groups
                            .observeMemberIds(science.id)
                            .first()
                    },
                )
                assertEquals(listOf(news.id, science.id), fixture.groups().map { it.id })
            } finally {
                try {
                    runOnIdle { showing = false }
                    waitForIdle()
                } finally {
                    fixture.close()
                    Locale.setDefault(previousLocale)
                }
            }
        }

    private fun ComposeUiTest.createGroup(
        name: String,
        members: List<Int>,
    ) {
        awaitNode(hasTestTag("groupName"))
        onNodeWithTag("groupName").performTextInput(name)
        for (id in members) {
            onNodeWithTag("groupEditorGrid").performScrollToNode(hasTestTag("groupMember$id"))
            onNodeWithTag("groupMember$id").performClick()
        }
        onNodeWithText("Save").performClick()
        awaitAbsent(hasTestTag("groupName"))
    }

    private fun ComposeUiTest.openManager() {
        onNodeWithContentDescription("All groups").performClick()
        awaitNode(hasText("Manage groups"))
        onNodeWithText("Manage groups").performClick()
        awaitNode(hasTestTag("groupsManageList"))
    }

    private fun ComposeUiTest.assertCount(
        group: Group,
        count: Int,
    ) {
        awaitNode(hasTestTag(group.tabTag()))
        waitUntil(timeoutMillis = TIMEOUT_MS) {
            onAllNodes(
                androidx.compose.ui.test
                    .hasContentDescription("${group.name}, $count unplayed, 0 new") and
                    hasAnyAncestor(hasTestTag(group.tabTag())),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ComposeUiTest.awaitNode(matcher: SemanticsMatcher) {
        waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        onNode(matcher).assertIsDisplayed()
    }

    private fun ComposeUiTest.awaitAbsent(matcher: SemanticsMatcher) {
        waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }
    }

    private fun Group.tabTag(): String = "feed-tab:group:$uuid"

    private fun Group.pageNode(text: String): SemanticsMatcher =
        hasText(text) and hasAnyAncestor(hasTestTag("feed-page:group:$uuid"))

    private fun Group.row(title: String): SemanticsMatcher = pageNode(title)

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private class JourneyFixture {
    private val clock = TestClock()
    private val db = TestDb.inMemory(clock = clock)

    // A separate virtual scheduler keeps the undo expiry frozen until explicitly advanced.
    private val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
    private val settings = FakeSettingsRepository()
    private val coordinator = FakeCredentialCommitCoordinator()
    private val podcasts =
        PodcastRepositoryImpl(
            db,
            newRefresher(db, emptyMap(), clock, settings),
            FakeRefreshScheduler(),
            JsoupShowNotesSanitizer(),
            clock,
            PodcastMerger(db, coordinator, clock),
            unusedPreviewResolver,
            FakeSecretStore(),
            coordinator,
            unusedProbeFetcher,
            scratchTempFiles("m2-journey"),
        )
    val graph = createGraphFactory<M2JourneyGraph.Factory>().create(db, clock, appScope, settings, podcasts)

    init {
        runBlocking {
            for ((index, title) in listOf("Alpha", "Beta", "Gamma").withIndex()) {
                db.podcastDao().insertPodcast(podcastEntity(id = index + 1L, title = title))
                db.episodeDao().insertStub(
                    episodeEntity(
                        id = index + 1L,
                        podcastId = index + 1L,
                        title = listOf("Unplayed member", "Played member", "Outside group")[index],
                        sortDate = clock.now() - (index + 1) * FeedPrefs.HOUR_MS,
                    ) { copy(enclosureUrl = "https://example.com/$id.mp3", enclosureType = "audio/mpeg") },
                )
            }
            db.episodeStateDao().upsert(episodeStateEntity(episodeId = 2, playedAt = clock.now()))
        }
    }

    fun groups(): List<Group> = runBlocking { graph.groups.observeGroups().first() }

    fun group(name: String): Group = groups().single { it.name == name }

    @Composable
    fun Content() {
        CompositionLocalProvider(
            LocalMetroViewModelFactory provides graph.viewModels,
            LocalPlatformKind provides PlatformKind.DESKTOP,
            LocalUiClock provides clock,
            LocalPlatformActions provides JourneyPlatformActions,
        ) {
            NeutrodyneRoot(
                state = RootUiState.READY,
                actions = RootActions({}, {}, {}, {}, { false }),
                slots = RootSlots(player = {}, userMessages = emptyFlow()),
                installers = graph.installers + UnusedDestinations,
                platform = BuildInfo.Platform.DESKTOP,
            )
        }
    }

    fun close() {
        appScope.cancel()
        db.close()
    }
}

/** Metro needs an internal graph so generated test code can implement it. */
@DependencyGraph(
    AppScope::class,
    excludes = [
        DataStoreSettingsRepository::class, PodcastRepositoryImpl::class,
        RefreshControllerImpl::class, DesktopNetworkMonitorBindings::class,
    ],
)
internal interface M2JourneyGraph {
    val installers: Set<EntryProviderInstaller>

    val viewModels: MetroViewModelFactory
    val groups: GroupRepository

    @Provides fun refresh(): RefreshController = FakeRefreshController()

    @Provides fun network(): NetworkMonitor = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)

    @Provides fun channels(): GroupChannelSync = NoGroupChannels

    @Provides fun orderKeys(): OrderKeys = TestOrderKeys

    @Provides
    @Dispatcher(Default)
    fun dispatcher(): CoroutineDispatcher = Dispatchers.Default

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides db: NeutrodyneDatabase,
            @Provides clock: Clock,
            @Provides @ApplicationScope appScope: CoroutineScope,
            @Provides settings: SettingsRepository,
            @Provides podcasts: PodcastRepository,
        ): M2JourneyGraph
    }
}

private val JourneyPlatformActions =
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

// The journey stays on Feeds and group routes; the root still owns five destination stacks.
private val UnusedDestinations: EntryProviderInstaller = {
    entry<LibraryKey>(metadata = NdSceneMetadata.paneList()) { Text("Unused Library") }
    entry<UpNextKey>(metadata = NdSceneMetadata.paneList()) { Text("Unused Up next") }
    entry<DownloadsKey>(metadata = NdSceneMetadata.paneList()) { Text("Unused Downloads") }
    entry<DiscoverKey>(metadata = NdSceneMetadata.paneList()) { Text("Unused Discover") }
}
