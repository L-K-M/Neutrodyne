// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne

import android.Manifest
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.core.view.WindowCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.model.FeedSourceRef
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.ThemeMode
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.groupEntity
import ch.lkmc.neutrodyne.core.testing.database.memberEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection.HTTP_OK
import java.net.HttpURLConnection.HTTP_PARTIAL
import java.util.UUID

/** Real production graph with offline rows; every seeded row is removed by its own ID. */
@RunWith(AndroidJUnit4::class)
class RadioHomeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = ApplicationProvider.getApplicationContext<NeutrodyneApplication>().graph
    private val podcastIds = mutableListOf<Long>()
    private val groupIds = mutableListOf<Long>()
    private var savedSelection: String? = null
    private var savedTheme: ThemeMode? = null
    private var database: NeutrodyneDatabase? = null
    private val server = MockWebServer()

    @After fun removeFixtures() =
        runBlocking(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                runCatching { graph.playbackController.pause() }
            }
            runCatching { graph.playbackController.dismiss() }
            runCatching { server.close() }
            val db = database ?: return@runBlocking
            savedSelection?.let {
                graph.settingsRepository.set(
                    AppearanceSettingKeys.FEEDS_SELECTED_SOURCE,
                    it,
                )
            }
            savedTheme?.let { graph.settingsRepository.set(AppearanceSettingKeys.THEME, it) }
            groupIds.forEach { db.groupDao().deleteGroup(it) }
            podcastIds.forEach { db.podcastDao().deleteCascade(it, 1_800_000_000_000L) }
        }

    @Test fun homeReadsGroupsAndSwipeUndoUsesPlayedState() {
        val ids =
            runBlocking(Dispatchers.IO) {
                graph.databaseOpener.awaitOpen()
                val db = graph.databaseOpener.requireDatabase().also { database = it }
                savedSelection = graph.settingsRepository.get(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE)
                savedTheme = graph.settingsRepository.get(AppearanceSettingKeys.THEME)
                graph.settingsRepository.set(AppearanceSettingKeys.THEME, ThemeMode.LIGHT)
                val nonce = UUID.randomUUID().toString()
                val shows = listOf("Signal & Sound", "Side Streets", "Open Circuit")
                shows.forEachIndexed { index, title ->
                    podcastIds +=
                        db.podcastDao().insertPodcast(
                            podcastEntity(
                                title = title,
                                feedUrl = "http://127.0.0.1:1/radio-$nonce-$index.xml",
                            ),
                        )
                }
                val row = groupEntity(name = "Morning rotation", orderKey = "a0")
                val group = db.groupDao().insertGroupOrIgnore(row)
                check(group > 0) { "Fixture name already exists; existing data was left untouched" }
                groupIds += group
                podcastIds.forEachIndexed {
                    index,
                    id,
                    ->
                    db.groupDao().insertMember(
                        memberEntity(
                            group,
                            id,
                            "a$index",
                        ),
                    )
                }
                val afterHours =
                    db.groupDao().insertGroupOrIgnore(
                        groupEntity(
                            name = "After hours",
                            orderKey = "a1",
                        ),
                    )
                check(afterHours > 0) { "Fixture name already exists; existing data was left untouched" }
                groupIds += afterHours
                podcastIds.drop(1).forEachIndexed {
                    index,
                    id,
                    ->
                    db.groupDao().insertMember(
                        memberEntity(
                            afterHours,
                            id,
                            "a$index",
                        ),
                    )
                }
                // The first slip is a real stream, like PlayJourneyTest's clip: the radio's Play
                // must drive the production PlaybackController, not a canned state.
                val frame =
                    InstrumentationRegistry
                        .getInstrumentation()
                        .context.assets.open("media/cbr_128k_2s.mp3")
                        .use { it.readBytes() }
                val body = Buffer().apply { repeat(STREAM_REPEATS) { write(frame) } }.readByteArray()
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val offset =
                                request.headers["Range"]
                                    ?.substringAfter("bytes=")
                                    ?.substringBefore('-')
                                    ?.toIntOrNull() ?: 0
                            if (offset !in body.indices) return MockResponse(code = HTTP_RANGE_NOT_SATISFIABLE)
                            return MockResponse
                                .Builder()
                                .code(if (offset == 0) HTTP_OK else HTTP_PARTIAL)
                                .addHeader("Content-Type", "audio/mpeg")
                                .addHeader("Accept-Ranges", "bytes")
                                .apply {
                                    if (offset > 0) {
                                        addHeader("Content-Range", "bytes $offset-${body.lastIndex}/${body.size}")
                                    }
                                }.body(Buffer().write(body, offset, body.size - offset))
                                .build()
                        }
                    }
                server.start()
                val titles =
                    listOf(
                        "The art of paying attention",
                        "The city after midnight",
                        "A slower kind of internet",
                        "Small rituals, big ideas",
                    )
                val durations = listOf(38, 32, 45, 23)
                val episodes =
                    titles.mapIndexed { index, title ->
                        db.episodeDao().insertStub(
                            episodeEntity(
                                podcastId = podcastIds[index % 3],
                                title = title,
                                sortDate = 1_800_000_000_000L - index,
                            ) {
                                copy(
                                    durationMs = durations[index] * 60_000L,
                                    enclosureUrl =
                                        if (index == 0) {
                                            server.url("/clip.mp3").toString()
                                        } else {
                                            "http://127.0.0.1:1/$index.mp3"
                                        },
                                )
                            },
                        )
                    }
                graph.settingsRepository.set(
                    AppearanceSettingKeys.FEEDS_SELECTED_SOURCE,
                    FeedSourceRef.Group(row.uuid).encode(),
                )
                episodes
            }
        compose.waitUntil(15_000) {
            compose
                .onAllNodes(hasTestTag("radio_episode_${ids.first()}"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("radio_episode_${ids.first()}").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("nav_feeds").assertDoesNotExist()
        compose.runOnUiThread {
            assertFalse(
                "The fixed dark cabinet needs light system-bar icons even in Light appearance",
                WindowCompat
                    .getInsetsController(
                        compose.activity.window,
                        compose.activity.window.decorView,
                    ).isAppearanceLightStatusBars,
            )
        }
        compose.onNodeWithTag("radio_menu").performClick()
        compose.onNodeWithTag("nav_library").performClick()
        compose.waitForIdle()
        compose.runOnUiThread {
            assertTrue(
                WindowCompat
                    .getInsetsController(
                        compose.activity.window,
                        compose.activity.window.decorView,
                    ).isAppearanceLightStatusBars,
            )
        }
        pressBack()
        compose.onNodeWithTag("radio_episode_${ids.first()}").assertIsDisplayed()
        compose.runOnUiThread {
            assertFalse(
                WindowCompat
                    .getInsetsController(
                        compose.activity.window,
                        compose.activity.window.decorView,
                    ).isAppearanceLightStatusBars,
            )
        }
        val capture = InstrumentationRegistry.getArguments().getString("radioScreenshot")
        if (capture != null && capture != "playing") {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File(context.getExternalFilesDir(null), "radio-$capture.png")
            check(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(file))
            if (InstrumentationRegistry.getArguments().getString("radioAwaitCapture") == "true") {
                val release = File(file.parentFile, "release-$capture")
                runBlocking(Dispatchers.IO) {
                    withTimeout(30_000) { while (!release.exists()) delay(100) }
                }
                check(release.delete())
            }
        }
        // The radio is the player on this screen: Play must reach the real controller and
        // engine, not a fixture flag. Playback is paused again before the interaction checks.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry
                .getInstrumentation()
                .uiAutomation
                .grantRuntimePermission(
                    InstrumentationRegistry.getInstrumentation().targetContext.packageName,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
        }
        compose.onNodeWithTag("radio_play").performClick()
        compose.waitUntil(30_000) { graph.playbackStateSource.nowPlaying.value?.isPlaying == true }
        compose.waitForIdle()
        if (capture == "playing") {
            // nowPlaying.position is a snapshot; the advancing position rides positionTicks.
            runBlocking {
                withTimeout(10_000) {
                    graph.playbackStateSource.positionTicks.first { it.positionMs >= 1_000 }
                }
            }
            compose.waitForIdle()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(
                UiDevice
                    .getInstance(InstrumentationRegistry.getInstrumentation())
                    .takeScreenshot(File(context.getExternalFilesDir(null), "radio-playing.png")),
            )
        }
        compose.runOnUiThread { graph.playbackController.pause() }
        compose.waitUntil(10_000) { graph.playbackStateSource.nowPlaying.value?.isPlaying == false }
        compose.onNodeWithTag("radio_new_playlist").performClick()
        compose.onNodeWithTag("radio_name").assertIsFocused()
        compose.onNodeWithTag("radio_create").assertIsDisplayed()
        if (capture == "phone") {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(
                UiDevice
                    .getInstance(
                        InstrumentationRegistry.getInstrumentation(),
                    ).takeScreenshot(File(context.getExternalFilesDir(null), "radio-phone-insert.png")),
            )
        }
        pressBack()
        compose.waitForIdle()
        if (compose.onAllNodes(hasTestTag("radio_insert")).fetchSemanticsNodes().isNotEmpty()) pressBack()
        compose.onNodeWithTag("radio_insert").assertDoesNotExist()
        compose.waitUntil(5_000) {
            compose.onNodeWithTag("radio_new_playlist").fetchSemanticsNode().config[SemanticsProperties.Focused]
        }
        compose.onNodeWithTag("radio_new_playlist").assertIsFocused()
        compose.onNodeWithTag("radio_playlist").performClick()
        compose.onNodeWithTag("radio_playlist_option_${groupIds.first()}").assertIsFocused()
        pressBack()
        compose.waitUntil(5_000) {
            compose.onNodeWithTag("radio_playlist").fetchSemanticsNode().config[SemanticsProperties.Focused]
        }
        compose.onNodeWithTag("radio_playlist").assertIsFocused()
        compose.onNodeWithTag("radio_episode_${ids.first()}").performTouchInput { swipeLeft() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("radio_undo")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("radio_episode_${ids[1]}").assertIsSelected()
        compose.onNodeWithTag("radio_undo").performClick()
        compose.onNodeWithTag("radio_episode_${ids.first()}").assertIsDisplayed()
        compose.onNodeWithTag("radio_episode_${ids[1]}").assertIsSelected()
    }

    private companion object {
        // The 2 s clip must outlast every playback assertion in the run.
        const val STREAM_REPEATS = 60
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}
