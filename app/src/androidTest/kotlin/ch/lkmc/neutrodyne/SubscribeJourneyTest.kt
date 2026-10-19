// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastGroupEntity
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.network.okhttp.DebugInterceptors
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.ReleaseSmoke
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.installFakeImageLoader
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.rememberAndroidPlatformActions
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.add_subscribe
import ch.lkmc.neutrodyne.core.ui.root.NeutrodyneRoot
import ch.lkmc.neutrodyne.core.ui.root.RootActions
import ch.lkmc.neutrodyne.core.ui.root.RootSlots
import ch.lkmc.neutrodyne.core.ui.root.RootUiState
import ch.lkmc.neutrodyne.di.AndroidAppGraph
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
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real Android parser/transport/Add UI, with recorded Apple lookup and a local feed server. */
@ReleaseSmoke
@RunWith(AndroidJUnit4::class)
class SubscribeJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    // The release manifest declares MainActivity; the empty Compose host is debug-only.
    private fun showJourney(content: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent(content = content) }
    }

    @Test
    fun sharedAppleLinkResolvesWithoutWritingUntilConfirmation() = journey(Entry.APPLE)

    @Test
    fun directFeedSchemeWaitsForSubscribe() = journey(Entry.FEED)

    @Test
    fun externalSubscribeWrapperWaitsForSubscribe() = journey(Entry.NEUTRODYNE)

    @Test
    fun sharedTextWaitsForSubscribe() = journey(Entry.SHARE)

    private fun journey(entry: Entry) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as NeutrodyneApplication
        val clock = TestClock()
        val db = TestDb.inMemory(application, driver = BundledSQLiteDriver(), clock = clock)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookups = AtomicInteger()
        val subscribeLabel = runBlocking { getString(Res.string.add_subscribe) }
        installFakeImageLoader()
        var showing by mutableStateOf(true)
        try {
            MockWebServer().use { server ->
                val feed =
                    instrumentation.context.assets
                        .open(
                            "discovery/journey-feed.xml",
                        ).bufferedReader()
                        .use { it.readText() }
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
                val lookup =
                    instrumentation.context.assets
                        .open(
                            "discovery/apple-lookup.json",
                        ).bufferedReader()
                        .use { it.readText() }
                        .replace("https://feeds.simplecast.com/h18ZIZD_", url)
                val replay =
                    Interceptor { chain ->
                        val request = chain.request()
                        if (request.url.host == "localhost" || request.url.host == "127.0.0.1") {
                            chain.proceed(request)
                        } else {
                            check(request.url.host == "itunes.apple.com" && request.url.encodedPath == "/lookup")
                            lookups.incrementAndGet()
                            Response
                                .Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(lookup.toResponseBody("application/json".toMediaType()))
                                .build()
                        }
                    }
                val graph =
                    createDynamicGraphFactory<AndroidAppGraph.Factory>(AppleJourneyBindings(db, clock, scope, replay))
                        .create(application)
                val routing = MainIntentViewModel(SavedStateHandle())
                val intent =
                    when (entry) {
                        Entry.FEED -> {
                            Intent(Intent.ACTION_VIEW, Uri.parse("feed:$url"))
                        }

                        Entry.NEUTRODYNE -> {
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("neutrodyne://subscribe?url=${Uri.encode(url)}"),
                            )
                        }

                        Entry.SHARE -> {
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                        }

                        Entry.APPLE -> {
                            Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, "https://podcasts.apple.com/us/podcast/id73329284")
                        }
                    }
                routing.receive(intent, application.packageName)
                val groupId =
                    runBlocking {
                        db.groupDao().insert(
                            PodcastGroupEntity(
                                uuid =
                                    java.util.UUID
                                        .randomUUID()
                                        .toString(),
                                name = "Journey group",
                                nameKey = "journey group",
                                orderKey = "a0",
                                createdAt = 0,
                                updatedAt = 0,
                            ),
                        )
                    }
                showJourney {
                    if (showing) {
                        CompositionLocalProvider(
                            LocalMetroViewModelFactory provides graph.metroViewModelFactory,
                            LocalUiClock provides clock,
                            LocalPlatformActions provides rememberAndroidPlatformActions(),
                        ) {
                            NeutrodyneRoot(
                                state = RootUiState.READY,
                                actions = RootActions({}, {}, {}, {}, { false }),
                                slots = RootSlots(player = {}, userMessages = emptyFlow()),
                                installers = graph.entryInstallers,
                                routes = routing.routes,
                            )
                        }
                    }
                }
                compose.waitUntil(
                    20_000,
                ) { compose.onAllNodes(hasText("M7 Subscribe Journey")).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("M7 Subscribe Journey").assertIsDisplayed()
                assertTrue(runBlocking { db.podcastDao().allForExport().isEmpty() })
                assertEquals(if (entry == Entry.APPLE) 1 else 0, lookups.get())
                val checkbox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
                compose.onNode(hasText("Journey group") and checkbox).performClick()
                compose.onNodeWithText(subscribeLabel).performClick()
                compose.waitUntil(20_000) { runBlocking { db.podcastDao().allForExport().isNotEmpty() } }
                val row = runBlocking { db.podcastDao().allForExport().single() }
                assertEquals(url, row.feedUrl)
                assertEquals(1, runBlocking { db.podcastDao().episodeCount(row.id) })
                assertTrue(
                    runBlocking {
                        db.groupDao().membershipPairs().any { pair ->
                            pair.podcastId == row.id && pair.groupId == groupId
                        }
                    },
                )
            }
        } finally {
            compose.runOnIdle { showing = false }
            compose.waitForIdle()
            scope.cancel()
            db.close()
        }
    }

    private enum class Entry { FEED, NEUTRODYNE, SHARE, APPLE }
}

@BindingContainer
private class AppleJourneyBindings(
    private val db: NeutrodyneDatabase,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val replay: Interceptor,
) {
    @Provides fun database(): NeutrodyneDatabase = db

    @Provides fun clock(): Clock = clock

    @Provides @ApplicationScope
    fun scope(): CoroutineScope = scope

    @Provides fun settings(): SettingsRepository = FakeSettingsRepository()

    @Provides @DebugInterceptors
    fun interceptors(): Set<Interceptor> = setOf(replay)
}
