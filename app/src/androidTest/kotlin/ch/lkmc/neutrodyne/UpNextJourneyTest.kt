// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.os.Build
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.neutrodyne.core.model.AddResult
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_undo
import ch.lkmc.neutrodyne.core.ui.resources.back
import ch.lkmc.neutrodyne.core.ui.resources.episode_no_notes
import ch.lkmc.neutrodyne.core.ui.resources.queue_move_down
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real-graph queue edits. API34+ touch actions run ATF; the journey must never start playback. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class) // performCustomAccessibilityActionWithLabel
class UpNextJourneyTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val queueOrder = AtomicReference(emptyList<Long>())
    private val playbackObserved = AtomicBoolean()

    // Collect off-main so waitUntil never blocks on Room.
    private val observation = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun stopObserving() {
        observation.cancel()
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun queueEditsPersistUndoRestoresOrderAndNothingPlays() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            compose.enableAccessibilityChecks()
        }
        val graph = ApplicationProvider.getApplicationContext<NeutrodyneApplication>().graph
        // Observe synchronously with publication to catch a transient start, not only final state.
        observation.launch(Dispatchers.Unconfined) {
            graph.playbackStateSource.nowPlaying.collect { if (it != null) playbackObserved.set(true) }
        }
        val ids =
            runBlocking(Dispatchers.IO) {
                withTimeout(OPEN_TIMEOUT_MS) { graph.databaseOpener.awaitOpen() }
                val db = graph.databaseOpener.requireDatabase()
                val podcast = db.podcastDao().insertPodcast(podcastEntity(title = PODCAST_TITLE))
                val seeded =
                    TITLES.mapIndexed { index, title ->
                        db.episodeDao().insertStub(
                            episodeEntity(podcastId = podcast, title = title, sortDate = SORT_DATE_BASE - index) {
                                copy(
                                    enclosureUrl = "https://example.invalid/episode-$index.mp3",
                                    durationMs = DURATION_MS,
                                )
                            },
                        )
                    }
                assertThat(graph.queueRepository().addLast(seeded)).isEqualTo(AddResult.Added(seeded.size))
                seeded
            }
        observation.launch {
            graph.queueRepository().observeUpNext().collect { items ->
                queueOrder.set(items.map { it.row.id })
            }
        }

        compose.waitUntil(STARTUP_TIMEOUT_MS) {
            compose
                .onAllNodes(hasTestTag(TAG_UP_NEXT))
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }
        compose.onNodeWithTag(TAG_UP_NEXT).performClick()
        awaitRows(ids)

        // The TalkBack path: the first row's "Move down" custom accessibility action.
        compose
            .onNodeWithText(TITLES[0])
            .performCustomAccessibilityActionWithLabel(text(Res.string.queue_move_down))
        awaitOrder(listOf(ids[1], ids[0]))

        // The thumb path: an actual touch swipe past the row's release threshold.
        compose.onNodeWithTag(rowTag(ids[1])).performTouchInput { swipeRight() }
        awaitOrder(listOf(ids[0]))

        val undo = text(Res.string.action_undo)
        compose.waitUntil(STARTUP_TIMEOUT_MS) {
            compose
                .onAllNodes(hasText(undo))
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }
        compose.onNodeWithText(undo).performClick()
        awaitOrder(listOf(ids[1], ids[0]))

        // A real tap on the row routes EpisodeAction.Open through the navigator to the detail.
        compose.onNodeWithText(TITLES[0]).performClick()
        val noNotes = text(Res.string.episode_no_notes)
        compose.waitUntil(STARTUP_TIMEOUT_MS) {
            compose
                .onAllNodes(hasText(noNotes))
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }
        // The title also exists in Up next; require detail-specific content.
        compose.onNodeWithText(noNotes).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(Res.string.back)).assertIsDisplayed()

        assertThat(queueOrder.get()).isEqualTo(listOf(ids[1], ids[0]))
        assertThat(playbackObserved.get()).isFalse()
        assertThat(graph.playbackStateSource.nowPlaying.value).isNull()
    }

    private fun awaitRows(ids: List<Long>) {
        for (id in ids) {
            compose.waitUntil(STARTUP_TIMEOUT_MS) {
                compose
                    .onAllNodes(hasTestTag(rowTag(id)))
                    .fetchSemanticsNodes(atLeastOneRootRequired = false)
                    .isNotEmpty()
            }
        }
        awaitOrder(ids)
    }

    private fun awaitOrder(expected: List<Long>) {
        compose.waitUntil(STARTUP_TIMEOUT_MS) { queueOrder.get() == expected }
    }

    private fun rowTag(id: Long) = "queueRow$id"

    private fun text(resource: StringResource): String = runBlocking { getString(resource) }

    private companion object {
        const val TAG_UP_NEXT = "nav_up_next"
        const val PODCAST_TITLE = "Queue fixture"
        val TITLES = listOf("Queue alpha", "Queue beta")
        const val SORT_DATE_BASE = 1_700_000_000_000L
        const val DURATION_MS = 600_000L
        const val STARTUP_TIMEOUT_MS = 10_000L
        const val OPEN_TIMEOUT_MS = 60_000L

        /** Covers the sequential worst case of every bounded wait plus the seed writes. */
        const val TEST_TIMEOUT_MS = 180_000L
    }
}
