// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PodcastGroupEntity
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.network.okhttp.DebugInterceptors
import ch.lkmc.neutrodyne.core.testing.FakeEngineSettings
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
import ch.lkmc.neutrodyne.youtube.engine.EngineSettings
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityViewCheckException
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real Android parser/transport/Add UI, with recorded Apple lookup and a local feed server. */
@ReleaseSmoke
@RunWith(AndroidJUnit4::class)
class SubscribeJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as NeutrodyneApplication
    private val device = UiDevice.getInstance(instrumentation)

    @OptIn(ExperimentalTestApi::class)
    @Before
    fun enableFrameworkAccessibilityChecks() {
        if (Build.VERSION.SDK_INT >= 34) compose.enableAccessibilityChecks()
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
                // 03 note 2026-11-02: a directory-named feed URL is a discovered destination and
                // may never be loopback; Apple's hit names a reserved public address the replay
                // answers without DNS or a connection, including under the DNS preflight guard.
                val feedUrl = if (entry == Entry.APPLE) APPLE_FEED_URL else url
                val lookup =
                    instrumentation.context.assets
                        .open(
                            "discovery/apple-lookup.json",
                        ).bufferedReader()
                        .use { it.readText() }
                        .replace("https://feeds.simplecast.com/h18ZIZD_", feedUrl)
                val replay =
                    Interceptor { chain ->
                        val request = chain.request()
                        if (request.url.host == "localhost" || request.url.host == "127.0.0.1") {
                            chain.proceed(request)
                        } else if (request.url.host == APPLE_FEED_HOST) {
                            Response
                                .Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(feed.toResponseBody("application/rss+xml".toMediaType()))
                                .build()
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
                compose.runOnUiThread {
                    compose.activity.setContent(parent = null) {
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
                }
                // 03 note 2026-11-02: a prefilled LAN URL does not auto-resolve — the user's
                // tap on Subscribe is the informed action that fetches it.
                if (entry != Entry.APPLE) {
                    compose.waitUntil(PREVIEW_TIMEOUT_MS) {
                        compose.onAllNodes(hasText(subscribeLabel)).fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithText(subscribeLabel).performScrollTo().performClickRecordingA11y(entry)
                }
                compose.awaitPreviewTitle(entry, lookups.get())
                compose.onNodeWithText("M7 Subscribe Journey").assertIsDisplayed()
                assertTrue(runBlocking { db.podcastDao().allForExport().isEmpty() })
                assertEquals(if (entry == Entry.APPLE) 1 else 0, lookups.get())
                val checkbox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
                compose.onNode(hasText("Journey group") and checkbox).performScrollTo().performClickRecordingA11y(entry)
                val subscribe = compose.onNodeWithText(subscribeLabel)
                subscribe.performScrollTo().performClickRecordingA11y(entry)
                compose.awaitSubscribeWrite(entry, subscribe, db)
                val row = runBlocking { db.podcastDao().allForExport().single() }
                assertEquals(feedUrl, row.feedUrl)
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

    /**
     * The preview wait, with the sheet's own words in the failure: a bare timeout cannot say
     * whether routing, resolution or rendering stalled (see docs/evidence/m7-discovery.md).
     */
    private fun ComposeContentTestRule.awaitPreviewTitle(
        entry: Entry,
        lookups: Int,
    ) {
        try {
            waitUntil(PREVIEW_TIMEOUT_MS) {
                onAllNodes(hasText("M7 Subscribe Journey")).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError(
                "No preview for $entry after $PREVIEW_TIMEOUT_MS ms (lookups=$lookups, " +
                    "on screen: ${shownTexts()})",
                timeout,
            )
        }
    }

    /**
     * The subscribe wait, with the button's own state in the failure: on the small api26 GMD
     * window the sheet scrolls, so a click on an off-screen button silently does nothing.
     */
    private fun ComposeContentTestRule.awaitSubscribeWrite(
        entry: Entry,
        subscribe: SemanticsNodeInteraction,
        db: NeutrodyneDatabase,
    ) {
        try {
            waitUntil(SUBSCRIBE_TIMEOUT_MS) { runBlocking { db.podcastDao().allForExport().isNotEmpty() } }
        } catch (timeout: ComposeTimeoutException) {
            val clickable = subscribe.fetchSemanticsNode().config.contains(SemanticsActions.OnClick)
            throw AssertionError(
                "No subscribe write for $entry (button clickable=$clickable, " +
                    "on screen: ${shownTexts()})",
                timeout,
            )
        }
    }

    private fun ComposeContentTestRule.shownTexts(): List<String> =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].joinToString(" ") }
            .filter { it.isNotBlank() }
            .distinct()
            .take(SHOWN_TEXTS)

    /**
     * API 37 ATF evidence capture. The TouchTargetSizeCheck failure names only a clipped
     * bounds ([136,24][184,53]), never the flagged node — so on a failed click, log the
     * flagged ATF elements and their parents (class, resource, description, clipped and
     * declared bounds), the real window dump, the semantics geometry and a screenshot,
     * then rethrow. The original failure is retained; the evidence lands in logcat and in
     * the app's external files dir for the device lane to pull.
     */
    private fun SemanticsNodeInteraction.performClickRecordingA11y(entry: Entry) {
        try {
            performClick()
        } catch (failure: RuntimeException) {
            recordA11yEvidence(entry, failure)
            throw failure
        }
    }

    private fun recordA11yEvidence(entry: Entry, failure: RuntimeException) {
        Log.i(A11Y_TAG, "entry=$entry failed with ${failure.javaClass.name}: ${failure.message}")
        if (failure is AccessibilityViewCheckException) {
            failure.results.forEachIndexed { index, result ->
                logA11yEvidence("atf[$index]") { describeA11yResult(result) }
            }
        }
        logA11yEvidence("window") { windowXml(entry) }
        logA11yEvidence("semantics") { semanticsGeometry() }
        logA11yEvidence("screenshot") { screenshot(entry) }
    }

    private fun describeA11yResult(result: AccessibilityViewCheckResult): String =
        buildString {
            append("check=").append(result.sourceCheckClass?.simpleName)
                .append(" type=").append(result.type)
                .append(" message=").append(result.message)
            val element = result.element
            if (element == null) {
                append("\n(no element recorded)")
                return@buildString
            }
            val window = element.window
            if (window != null) {
                append("\nwindow type=").append(window.type)
                    .append(" layer=").append(window.layer)
                    .append(" bounds=").append(window.boundsInScreen)
            }
            var node: ViewHierarchyElement? = element
            while (node != null) {
                append("\nelement ").append(describeElement(node))
                node = node.parentView
            }
        }

    private fun describeElement(element: ViewHierarchyElement): String =
        "class=${element.className} a11yClass=${element.accessibilityClassName} " +
            "res=${element.resourceName} tag=${element.testTag} cd=${element.contentDescription} " +
            "text=${element.text} state=${element.stateDescription} bounds=${element.boundsInScreen} " +
            "nonclipped=${element.nonclippedWidth}x${element.nonclippedHeight} " +
            "click=${element.isClickable} longClick=${element.isLongClickable} " +
            "important=${element.isImportantForAccessibility} visible=${element.isVisibleToUser} " +
            "touchDelegate=${element.touchDelegateBounds} origin=${element.origin}"

    private fun windowXml(entry: Entry): String {
        val dump = File(evidenceDir(), "window-${entry.name.lowercase()}.xml")
        device.dumpWindowHierarchy(dump)
        return dump.readText()
    }

    private fun semanticsGeometry(): String =
        buildString {
            compose
                .onAllNodes(SemanticsMatcher("all") { true }, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .forEach { node ->
                    val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("|")
                    val cd = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("|")
                    append(
                        "node id=${node.id} pos=${node.positionInRoot} size=${node.size} " +
                            "bounds=${node.boundsInRoot} touch=${node.touchBoundsInRoot} " +
                            "win=${node.boundsInWindow} text=$text cd=$cd " +
                            "click=${node.config.contains(SemanticsActions.OnClick)}\n",
                    )
                }
        }

    private fun screenshot(entry: Entry): String {
        val shot = File(evidenceDir(), "a11y-${entry.name.lowercase()}.png")
        return if (device.takeScreenshot(shot)) "wrote ${shot.absolutePath}" else "takeScreenshot returned false"
    }

    private fun evidenceDir(): File =
        File(application.getExternalFilesDir(null), "api37-subscribe").apply { mkdirs() }

    private fun logA11yEvidence(name: String, capture: () -> String) {
        val text = runCatching(capture).getOrElse { "capture failed: $it" }
        val chunks = text.chunked(A11Y_LOG_CHUNK)
        chunks.forEachIndexed { index, chunk -> Log.i(A11Y_TAG, "$name[$index/${chunks.size}] $chunk") }
        Log.i(A11Y_TAG, "$name complete chars=${text.length}")
    }

    private companion object {
        const val PREVIEW_TIMEOUT_MS = 20_000L
        const val SUBSCRIBE_TIMEOUT_MS = 20_000L
        const val SHOWN_TEXTS = 12
        const val A11Y_TAG = "SubscribeJourneyA11y"
        const val A11Y_LOG_CHUNK = 1_000

        // TEST-NET-1 (RFC 5737): replayed below the preflight, never sent to a real peer.
        const val APPLE_FEED_HOST = "192.0.2.1"
        const val APPLE_FEED_URL = "https://$APPLE_FEED_HOST/feed.xml"
    }
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

    @Provides fun engineSettings(): EngineSettings = FakeEngineSettings()

    @Provides @DebugInterceptors
    fun interceptors(): Set<Interceptor> = setOf(replay)
}
