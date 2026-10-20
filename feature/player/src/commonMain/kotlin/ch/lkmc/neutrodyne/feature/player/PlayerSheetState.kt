// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneMotion

public enum class PlayerSheetValue { Collapsed, Expanded, Dismissed }

/** Measured anchors and predictive preview share one morph; preview never commits navigation. */
@Stable
public class PlayerSheetState(
    initial: PlayerSheetValue = PlayerSheetValue.Collapsed,
) {
    public val drag: AnchoredDraggableState<PlayerSheetValue> = AnchoredDraggableState(initial)
    private var collapsedPx by mutableFloatStateOf(0f)
    private var backProgress by mutableStateOf<Float?>(null)
    public var dismissible: Boolean by mutableStateOf(false)
        private set

    public val progress: Float
        get() {
            backProgress?.let { return 1f - it * BACK_PREVIEW_FRACTION }
            val offset = drag.offset
            if (!offset.isFinite() || collapsedPx <= 0f) return if (isExpanded) 1f else 0f
            return (1f - offset / collapsedPx).coerceIn(0f, 1f)
        }

    public val isExpanded: Boolean get() = drag.currentValue == PlayerSheetValue.Expanded
    public val settledExpanded: Boolean
        get() = drag.settledValue == PlayerSheetValue.Expanded && !drag.isAnimationRunning && progress == 1f

    public fun setAnchors(
        collapsedPx: Float,
        dismissDistancePx: Float,
        dismissible: Boolean,
    ) {
        this.collapsedPx = collapsedPx.coerceAtLeast(0f)
        this.dismissible = dismissible
        val anchors =
            DraggableAnchors {
                PlayerSheetValue.Expanded at 0f
                PlayerSheetValue.Collapsed at this@PlayerSheetState.collapsedPx
                if (dismissible) PlayerSheetValue.Dismissed at this@PlayerSheetState.collapsedPx + dismissDistancePx
            }
        val target =
            if (!dismissible &&
                drag.targetValue == PlayerSheetValue.Dismissed
            ) {
                PlayerSheetValue.Collapsed
            } else {
                drag.targetValue
            }
        drag.updateAnchors(anchors, target)
    }

    public suspend fun expand(reducedMotion: Boolean = false) = animate(PlayerSheetValue.Expanded, reducedMotion)

    public suspend fun collapse(reducedMotion: Boolean = false) = animate(PlayerSheetValue.Collapsed, reducedMotion)

    public suspend fun resetDismissed() = drag.snapTo(PlayerSheetValue.Collapsed)

    private suspend fun animate(
        value: PlayerSheetValue,
        reducedMotion: Boolean,
    ) {
        backProgress?.let { preview ->
            if (drag.offset.isFinite()) {
                drag.dispatchRawDelta(collapsedPx * preview * BACK_PREVIEW_FRACTION - drag.offset)
            }
        }
        backProgress = null
        val spec: FiniteAnimationSpec<Float> = NeutrodyneMotion.spatial(reducedMotion)
        drag.animateTo(value, spec)
    }

    public fun previewBack(progress: Float) {
        backProgress = progress.coerceIn(0f, 1f)
    }

    public fun cancelBack() {
        backProgress = null
    }

    public suspend fun cancelBack(reducedMotion: Boolean) {
        animate(PlayerSheetValue.Expanded, reducedMotion)
    }

    public suspend fun commitBack(reducedMotion: Boolean = false) {
        collapse(reducedMotion)
    }

    public companion object {
        private const val BACK_PREVIEW_FRACTION = 0.2f
        public val Saver: Saver<PlayerSheetState, String> =
            Saver(
                save = { if (it.isExpanded) PlayerSheetValue.Expanded.name else PlayerSheetValue.Collapsed.name },
                restore = {
                    PlayerSheetState(
                        if (it ==
                            PlayerSheetValue.Expanded.name
                        ) {
                            PlayerSheetValue.Expanded
                        } else {
                            PlayerSheetValue.Collapsed
                        },
                    )
                },
            )
    }
}

@Composable
public fun rememberPlayerSheetState(): PlayerSheetState =
    rememberSaveable(saver = PlayerSheetState.Saver) {
        PlayerSheetState()
    }
