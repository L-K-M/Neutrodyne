// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.foundation.gestures.snapTo
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.saveable.SaverScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerSheetStateTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun predictiveCommitStartsAtThePreviewRatherThanJumpingBack() =
        runTest {
            val state = PlayerSheetState(PlayerSheetValue.Expanded)
            state.setAnchors(600f, 96f, true)
            state.previewBack(0.5f)
            val noFrames =
                object : MonotonicFrameClock {
                    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = awaitCancellation()
                }
            backgroundScope.launch(noFrames) { state.commitBack() }
            runCurrent()
            assertEquals(0.9f, state.progress)
        }

    @Test fun measuredAnchorsOnlyOfferDismissWhilePaused() {
        val state = PlayerSheetState()
        state.setAnchors(600f, 96f, true)
        assertEquals(0f, state.drag.anchors.positionOf(PlayerSheetValue.Expanded))
        assertEquals(600f, state.drag.anchors.positionOf(PlayerSheetValue.Collapsed))
        assertEquals(696f, state.drag.anchors.positionOf(PlayerSheetValue.Dismissed))
        state.setAnchors(600f, 96f, false)
        assertTrue(
            state.drag.anchors
                .positionOf(PlayerSheetValue.Dismissed)
                .isNaN(),
        )
        assertFalse(state.dismissible)
    }

    @Test fun removingDismissAnchorReturnsToCollapsed() =
        runTest {
            val state = PlayerSheetState()
            state.setAnchors(600f, 96f, true)
            state.drag.snapTo(PlayerSheetValue.Dismissed)
            state.setAnchors(700f, 96f, false)
            assertEquals(PlayerSheetValue.Collapsed, state.drag.targetValue)
        }

    @Test fun saveAndRestoreExpandedAndCollapsedButNeverDismissed() =
        runTest {
            val scope =
                object : SaverScope {
                    override fun canBeSaved(value: Any): Boolean = true
                }
            val state = PlayerSheetState()
            state.setAnchors(600f, 96f, true)
            for (value in PlayerSheetValue.entries) {
                state.drag.snapTo(value)
                val saved = with(PlayerSheetState.Saver) { scope.save(state) }
                val restored = PlayerSheetState.Saver.restore(requireNotNull(saved))!!
                assertEquals(
                    if (value ==
                        PlayerSheetValue.Expanded
                    ) {
                        value
                    } else {
                        PlayerSheetValue.Collapsed
                    },
                    restored.drag.currentValue,
                )
            }
        }

    @Test fun predictivePreviewDoesNotCommitAndCancelRestoresOriginal() {
        val state = PlayerSheetState(PlayerSheetValue.Expanded)
        state.setAnchors(600f, 96f, true)
        state.previewBack(0.5f)
        assertEquals(0.9f, state.progress)
        assertEquals(PlayerSheetValue.Expanded, state.drag.currentValue)
        state.cancelBack()
        assertEquals(1f, state.progress)
        assertTrue(state.settledExpanded)
    }

    @Test fun resizeKeepsExpandedAnchorAndProgress() {
        val state = PlayerSheetState(PlayerSheetValue.Expanded)
        state.setAnchors(600f, 96f, true)
        state.setAnchors(800f, 96f, true)
        assertEquals(0f, state.drag.offset)
        assertEquals(1f, state.progress)
    }
}
