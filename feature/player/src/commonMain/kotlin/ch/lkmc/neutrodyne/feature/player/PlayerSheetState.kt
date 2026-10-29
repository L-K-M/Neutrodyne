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

internal enum class PlayerSheetValue { Collapsed, Expanded, Dismissed }

internal enum class SheetMotion { ANIMATED, IMMEDIATE }

internal enum class SheetDismissal { ALLOWED, BLOCKED }

/** Measured anchors and predictive preview share one morph; preview never commits navigation. */
@Stable
internal class PlayerSheetState(
    initial: PlayerSheetValue = PlayerSheetValue.Collapsed,
) {
    public val drag: AnchoredDraggableState<PlayerSheetValue> = AnchoredDraggableState(initial)
    private var collapsedPx by mutableFloatStateOf(0f)
    private var backProgress by mutableStateOf<Float?>(null)
    private var dismissible: Boolean by mutableStateOf(false)

    public val progress: Float
        get() {
            backProgress?.let { return 1f - it * BACK_PREVIEW_FRACTION }
            val offset = drag.offset
            if (!offset.isFinite() || collapsedPx <= 0f) return if (isExpanded) 1f else 0f
            return (1f - offset / collapsedPx).coerceIn(0f, 1f)
        }

    public val isExpanded: Boolean get() = drag.currentValue == PlayerSheetValue.Expanded
    public val canCollapse: Boolean get() = progress > 0f || drag.targetValue == PlayerSheetValue.Expanded
    public val settledExpanded: Boolean
        get() = drag.settledValue == PlayerSheetValue.Expanded && !drag.isAnimationRunning && progress == 1f

    public fun setAnchors(
        collapsedPx: Float,
        dismissDistancePx: Float,
        dismissal: SheetDismissal,
    ) {
        this.collapsedPx = collapsedPx.coerceAtLeast(0f)
        dismissible = dismissal == SheetDismissal.ALLOWED
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

    public suspend fun expand(motion: SheetMotion = SheetMotion.ANIMATED) = animate(PlayerSheetValue.Expanded, motion)

    public suspend fun collapse(motion: SheetMotion = SheetMotion.ANIMATED) =
        animate(PlayerSheetValue.Collapsed, motion)

    public suspend fun resetDismissed() = drag.snapTo(PlayerSheetValue.Collapsed)

    private suspend fun animate(
        value: PlayerSheetValue,
        motion: SheetMotion,
    ) {
        backProgress?.let { preview ->
            if (drag.offset.isFinite()) {
                drag.dispatchRawDelta(collapsedPx * preview * BACK_PREVIEW_FRACTION - drag.offset)
            }
        }
        backProgress = null
        val spec: FiniteAnimationSpec<Float> = NeutrodyneMotion.spatial(motion == SheetMotion.IMMEDIATE)
        drag.animateTo(value, spec)
    }

    public fun previewBack(progress: Float) {
        backProgress = progress.coerceIn(0f, 1f)
    }

    public fun cancelBack() {
        backProgress = null
    }

    public suspend fun cancelBack(motion: SheetMotion) {
        animate(PlayerSheetValue.Expanded, motion)
    }

    public suspend fun commitBack(motion: SheetMotion = SheetMotion.ANIMATED) {
        collapse(motion)
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
internal fun rememberPlayerSheetState(): PlayerSheetState =
    rememberSaveable(saver = PlayerSheetState.Saver) {
        PlayerSheetState()
    }
