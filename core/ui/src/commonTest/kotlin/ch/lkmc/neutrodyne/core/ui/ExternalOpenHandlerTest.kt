// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExternalOpenHandlerTest {
    @Test fun failedBrowserOpenAndCancelledActionNeverMark() =
        runTest {
            var writes = 0
            val messages = UserMessages()
            val handler =
                ExternalOpenHandler(this, messages, {
                    writes++
                    true
                }, {}, { throw it })
            val action = EpisodeAction.WatchOnYouTube(7, "dQw4w9WgXcQ")
            val result =
                dispatchEpisodeRoute(
                    action,
                    RecordingAppNavigator(),
                    ExternalUrlOpener { OpenResult.NO_HANDLER },
                )
            if (result == EpisodeRouteResult.HANDLED) handler.opened(action.episodeId)
            runCurrent()
            assertEquals(0, writes)
            assertEquals(emptyList(), messages.flow.value)
            // A cancelled platform interaction has no successful handoff callback.
            runCurrent()
            assertEquals(0, writes)
        }

    @Test fun openedMarksOnceOffersFiveSecondUndoAndEvictsActions() =
        runTest {
            val messages = UserMessages()
            val marks = mutableListOf<Long>()
            val undos = mutableListOf<Long>()
            val handler =
                ExternalOpenHandler(this, messages, {
                    marks += it
                    true
                }, { undos += it }, { throw it })
            handler.opened(7)
            runCurrent()
            val message = messages.flow.value.single()
            assertEquals(5_000L, message.durationMs)
            handler.onAction(message.id)
            handler.onAction(message.id)
            runCurrent()
            assertEquals(listOf(7L), undos)
            handler.opened(8)
            handler.opened(9, YouTubeWatchMode.PLAYABLE_OVERFLOW)
            runCurrent()
            handler.shown(
                messages.flow.value
                    .last()
                    .id,
            )
            handler.onAction(
                messages.flow.value
                    .last()
                    .id,
            )
            runCurrent()
            assertEquals(listOf(7L, 8L), marks)
            assertEquals(listOf(7L), undos)
        }

    @Test fun disabledPreferenceDoesNotOfferUndo() =
        runTest {
            val messages = UserMessages()
            val handler = ExternalOpenHandler(this, messages, { false }, {}, { throw it })
            handler.opened(7)
            runCurrent()
            assertEquals(emptyList(), messages.flow.value)
        }
}
