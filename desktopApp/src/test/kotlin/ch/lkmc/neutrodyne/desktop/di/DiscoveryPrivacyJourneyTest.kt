// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.di

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.model.DirectoryHit
import ch.lkmc.neutrodyne.core.model.DirectoryKeySource
import ch.lkmc.neutrodyne.core.model.ProviderId
import ch.lkmc.neutrodyne.core.model.ProviderInfo
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.core.model.SearchResults
import ch.lkmc.neutrodyne.core.navigation.DirectoryKey
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.IntentRouter
import ch.lkmc.neutrodyne.core.navigation.Route
import ch.lkmc.neutrodyne.core.navigation.RouteInput
import ch.lkmc.neutrodyne.core.network.okhttp.LocalNetworkUnsupportedException
import ch.lkmc.neutrodyne.core.testing.FakeSearchRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.root.NeutrodyneRoot
import ch.lkmc.neutrodyne.core.ui.root.RootActions
import ch.lkmc.neutrodyne.core.ui.root.RootSlots
import ch.lkmc.neutrodyne.core.ui.root.RootUiState
import ch.lkmc.neutrodyne.desktop.buildinfo.BuildInfoLoader
import ch.lkmc.neutrodyne.desktop.crash.DesktopCrashReporter
import ch.lkmc.neutrodyne.desktop.log.RecentLogBuffer
import ch.lkmc.neutrodyne.desktop.shell.tempAppDirs
import coil3.EventListener
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import dev.zacsweers.metro.createDynamicGraphFactory
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real share routing, resolution and Coil rendering, without live providers or a fake loader. */
@OptIn(ExperimentalTestApi::class)
class DiscoveryPrivacyJourneyTest {
    @Test fun addSearchWaitsForItsRecipientsAndDirectoryKeepsThem() = journey(Entry.DISCLOSURE)

    @Test fun encodedShareWaitsForTap() = journey(Entry.ENCODED_SHARE)

    @Test fun idnaShareWaitsForTap() = journey(Entry.IDNA_SHARE)

    @Test fun addArtworkRefusesLocalPeer() = journey(Entry.ADD_ART)

    @Test fun addArtworkRefusesLocalRedirect() = journey(Entry.ADD_REDIRECT_ART)

    @Test fun directoryArtworkRefusesLocalPeer() = journey(Entry.DIRECTORY_ART)

    @Test fun chartArtworkRefusesLocalPeer() = journey(Entry.CHART_ART)

    @Test fun podcastPreviewArtworkRefusesLocalPeer() = journey(Entry.PREVIEW_ART)

    private fun journey(entry: Entry) =
        runComposeUiTest {
            val previous = Locale.getDefault()
            Locale.setDefault(Locale.ENGLISH)
            val clock = TestClock()
            val db = TestDb.inMemory(clock = clock)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val dirs = tempAppDirs("m7-privacy").also { it.ensureCreated() }
            val build = BuildInfoLoader.load()
            val reporter = DesktopCrashReporter(dirs, build, clock, RecentLogBuffer())
            val search = FakeSearchRepository()
            if (entry == Entry.DISCLOSURE) {
                search.providersGate = CompletableDeferred()
                search.providers.value = listOf(
                    ProviderInfo(ProviderId.APPLE, true),
                    ProviderInfo(ProviderId.PODCAST_INDEX, true, keySource = DirectoryKeySource.USER),
                    ProviderInfo(ProviderId.FYYD, false),
                ).toImmutableList()
            }
            val graph =
                createDynamicGraphFactory<M2JourneyGraph.Factory>(DiscoveryJourneyBindings(db, clock, scope, search))
                    .create(dirs, build, reporter)
            val failures = ConcurrentLinkedQueue<Throwable>()
            val original = graph.imageLoaderFactory.newImageLoader(PlatformContext.INSTANCE)
            val loader = original.newBuilder().eventListenerFactory {
                object : EventListener() {
                    override fun onError(request: ImageRequest, result: ErrorResult) {
                        failures.add(result.throwable)
                    }
                }
            }.build()
            SingletonImageLoader.setUnsafe { loader }
            var showing by mutableStateOf(true)
            try {
                MockWebServer().use { server ->
                    MockWebServer().use { lan ->
                        server.start()
                        lan.start(InetAddress.getByName("127.0.0.2"), server.port)
                        lan.enqueue(MockResponse.Builder().body("LAN honeypot").build())
                        val publicFeed = server.url("/feed.xml").newBuilder().host("journey.test").build().toString()
                        val localArt = lan.url("/private-art.png").toString()
                        val art = if (entry == Entry.ADD_REDIRECT_ART) {
                            server.url("/redirect-art").newBuilder().host("journey.test").build().toString()
                        } else {
                            localArt
                        }
                        val feed = File("../feeds/src/test/resources/discovery/journey-feed.xml").readText()
                            .replace("</channel>", "<image><url>$art</url></image></channel>")
                        server.dispatcher = object : Dispatcher() {
                            override fun dispatch(request: RecordedRequest): MockResponse =
                                if (request.target == "/redirect-art") {
                                    MockResponse.Builder().code(302).addHeader("Location", localArt).build()
                                } else {
                                    MockResponse.Builder().body(feed).build()
                                }
                        }
                        val hit = DirectoryHit(
                            publicFeed,
                            "Directory show",
                            artworkUrl = if (entry == Entry.PREVIEW_ART) null else localArt,
                            providers = persistentSetOf(ProviderId.APPLE),
                        )
                        search.searchAnswer = { query ->
                            flowOf(SearchResults(query, persistentListOf(hit), persistentMapOf(ProviderId.APPLE to ProviderStatus.Ok), true))
                        }
                        search.chartAnswer = Outcome.Success(persistentListOf(hit))
                        val route = when (entry) {
                            Entry.DISCLOSURE -> shared("medical history")
                            Entry.ENCODED_SHARE -> shared("http://%31%32%37.0.0.2:${server.port}/action")
                            Entry.IDNA_SHARE -> shared("http://１２７.０.０.２:${server.port}/action")
                            Entry.ADD_ART, Entry.ADD_REDIRECT_ART -> shared(publicFeed)
                            Entry.CHART_ART -> Route.Navigate(DiscoverKey, emptyList())
                            else -> Route.Navigate(DiscoverKey, listOf(DirectoryKey("history", null)))
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
                                        platform = build.platform,
                                        routes = flowOf(route),
                                    )
                                }
                            }
                        }
                        if (entry == Entry.PREVIEW_ART) {
                            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Directory show")).fetchSemanticsNodes().isNotEmpty() }
                            onNodeWithText("Directory show").performClick()
                        }
                        if (entry == Entry.DISCLOSURE) {
                            val action = "Search for ‘medical history’"
                            val disclosure = "Searches are sent to Apple, Podcast Index"
                            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(action)).fetchSemanticsNodes().isNotEmpty() }
                            onNodeWithText(action).assertIsNotEnabled().performClick()
                            onNodeWithText("Enable a directory in Discover settings").assertDoesNotExist()
                            assertTrue(search.queries.isEmpty())
                            runOnIdle { search.providersGate!!.complete(Unit) }
                            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(disclosure)).fetchSemanticsNodes().isNotEmpty() }
                            onNodeWithText(disclosure).assertIsDisplayed()
                            onNodeWithText(action).performClick()
                            waitUntil(timeoutMillis = 10_000) { search.queries.isNotEmpty() }
                            waitForIdle()
                            onNodeWithText(disclosure).assertIsDisplayed()
                            assertEquals(listOf("medical history"), search.queries)
                        } else if (entry == Entry.ENCODED_SHARE || entry == Entry.IDNA_SHARE) {
                            waitForIdle()
                            onNodeWithText("Subscribe").assertIsDisplayed()
                            onNodeWithText("Looking up…").assertDoesNotExist()
                            assertEquals(0, server.requestCount)
                        } else {
                            waitUntil(timeoutMillis = 10_000) { failures.isNotEmpty() }
                            assertTrue(failures.all { error ->
                                generateSequence(error) { it.cause }.any { it is LocalNetworkUnsupportedException }
                            }, failures.toString())
                            if (entry == Entry.ADD_REDIRECT_ART) assertEquals(2, server.requestCount)
                        }
                        assertEquals(0, lan.requestCount)
                        assertTrue(runBlocking { db.podcastDao().allForExport().isEmpty() })
                    }
                }
            } finally {
                runOnIdle { showing = false }
                waitForIdle()
                installFakeImageLoader()
                loader.shutdown()
                original.shutdown()
                scope.cancel()
                db.close()
                dirs.data.parent.toFile().deleteRecursively()
                Locale.setDefault(previous)
            }
        }

    private fun shared(text: String): Route = IntentRouter.route(RouteInput(RouteInput.Action.SEND, text = text))

    private enum class Entry { DISCLOSURE, ENCODED_SHARE, IDNA_SHARE, ADD_ART, ADD_REDIRECT_ART, DIRECTORY_ART, CHART_ART, PREVIEW_ART }
}
