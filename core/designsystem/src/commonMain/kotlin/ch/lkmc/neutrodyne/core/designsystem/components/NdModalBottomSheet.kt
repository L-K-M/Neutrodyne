// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.resources.Res
import ch.lkmc.neutrodyne.core.designsystem.resources.sheet_collapse
import ch.lkmc.neutrodyne.core.designsystem.resources.sheet_dismiss
import ch.lkmc.neutrodyne.core.designsystem.resources.sheet_drag_handle
import ch.lkmc.neutrodyne.core.designsystem.resources.sheet_expand
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneShapes
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

public enum class NdSheetExpansion { EXPANDED, HALF }

/**
 * `ModalBottomSheet` with the Neutrodyne sheet top corners (28 dp, `NeutrodyneShapes.Sheet`). On
 * desktop this is window-backed (Compose `Dialog`), so navigation-level sheets render above the
 * expanded player — that is what the shared `NdSheetSceneStrategy` relies on. The sheet state stays
 * inside: `SheetState` is an experimental Material 3 type, and `Nd*` wrappers never expose those
 * (08 Theming and colour). The All groups picker can start half height; other sheets expand fully.
 *
 * The drag handle is composed after the content instead of through Material's `dragHandle` slot:
 * Android exports accessibility bounds by walking semantics children in reverse composition order
 * and subtracting each node's unclipped touch bounds from the space later siblings may claim
 * (Compose UI 1.12.1 `SemanticsOwner.getAllUncoveredSemanticsNodesToIntObjectMap`). Material
 * composes the slot before the content, so a scrolled content node whose touch bounds poke above
 * sheet's scroll viewport (an editable field keeps them unclipped) claims the lower band of the
 * handle first and the platform exports the handle clipped to 24-29 dp — the API 37 Subscribe ATF
 * `TouchTargetSizeCheck` failures. Composed after the content, the handle claims its full 48 dp
 * first and the scrolled node is notched instead. TalkBack therefore reaches the handle after the
 * sheet content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun NdModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = NeutrodyneShapes.Sheet,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    shouldDismissOnBackPress: Boolean = true,
    initialExpansion: NdSheetExpansion = NdSheetExpansion.EXPANDED,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = initialExpansion == NdSheetExpansion.EXPANDED,
        )
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColorFor(containerColor),
        sheetMaxWidth = sheetMaxWidth,
        dragHandle = null,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = shouldDismissOnBackPress),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(top = SHEET_HANDLE_MIN_TARGET),
                content = content,
            )
            SheetDragHandleAnchor(
                sheetState = sheetState,
                onDismissRequest = onDismissRequest,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/**
 * Material 1.4.0's drag-handle wrapper (`ModalBottomSheet.kt`'s `DragHandleWithTooltip` and its
 * clickable box with dismiss/expand/collapse semantics), rebuilt on public APIs so it can be
 * composed after the content (see [NdModalBottomSheet]). Two details of Material's version call
 * `SheetState.anchoredDraggableState.confirmValueChange`, which is internal: they gate the same
 * `hide()`/`expand()`/`partialExpand()` calls on `SheetState.confirmValueChange`. That gate is a
 * no-op here because [NdModalBottomSheet] always creates the state with the default
 * always-accepting callback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetDragHandleAnchor(
    sheetState: SheetState,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val animateToDismiss: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismissRequest()
        }
    }
    val collapseLabel = stringResource(Res.string.sheet_collapse)
    val dismissLabel = stringResource(Res.string.sheet_dismiss)
    val expandLabel = stringResource(Res.string.sheet_expand)
    Box(modifier) {
        TooltipBox(
            positionProvider =
                TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = {
                PlainTooltip { Text(stringResource(Res.string.sheet_drag_handle)) }
            },
            state = rememberTooltipState(),
        ) {
            Box(
                Modifier
                    .clickable {
                        when (sheetState.currentValue) {
                            SheetValue.Expanded -> animateToDismiss()
                            SheetValue.PartiallyExpanded -> scope.launch { sheetState.expand() }
                            else -> scope.launch { sheetState.show() }
                        }
                    }.semantics(mergeDescendants = true) {
                        with(sheetState) {
                            dismiss(dismissLabel) {
                                animateToDismiss()
                                true
                            }
                            if (currentValue == SheetValue.PartiallyExpanded) {
                                expand(expandLabel) {
                                    scope.launch { sheetState.expand() }
                                    true
                                }
                            } else if (hasPartiallyExpandedState) {
                                collapse(collapseLabel) {
                                    scope.launch { sheetState.partialExpand() }
                                    true
                                }
                            }
                        }
                    },
            ) {
                SheetDragHandle()
            }
        }
    }
}

/**
 * The stock visual on a node that spans a real 48 dp target. Material 1.4.0 attaches its handle
 * describing semantics to a 32 dp-wide layout, which the on-device ATF audit measures as a touch
 * target and fails 08's 48 dp minimum (Subscribe journey evidence).
 */
@Composable
private fun SheetDragHandle() {
    val description = stringResource(Res.string.sheet_drag_handle)
    Box(
        Modifier
            .semantics { contentDescription = description }
            .sizeIn(minWidth = SHEET_HANDLE_MIN_TARGET, minHeight = SHEET_HANDLE_MIN_TARGET)
            .padding(vertical = SHEET_HANDLE_VERTICAL_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(SHEET_HANDLE_WIDTH, SHEET_HANDLE_HEIGHT)
                .background(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.shapes.extraLarge),
        )
    }
}

private val SHEET_HANDLE_VERTICAL_PADDING = 22.dp
private val SHEET_HANDLE_MIN_TARGET = 48.dp
private val SHEET_HANDLE_WIDTH = 32.dp
private val SHEET_HANDLE_HEIGHT = 4.dp
