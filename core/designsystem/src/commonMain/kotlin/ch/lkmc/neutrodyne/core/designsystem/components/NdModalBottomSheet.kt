// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.resources.Res
import ch.lkmc.neutrodyne.core.designsystem.resources.sheet_drag_handle
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneShapes
import org.jetbrains.compose.resources.stringResource

public enum class NdSheetExpansion { EXPANDED, HALF }

/**
 * `ModalBottomSheet` with the Neutrodyne sheet top corners (28 dp, `NeutrodyneShapes.Sheet`). On
 * desktop this is window-backed (Compose `Dialog`), so navigation-level sheets render above the
 * expanded player — that is what the shared `NdSheetSceneStrategy` relies on. The sheet state stays
 * inside: `SheetState` is an experimental Material 3 type, and `Nd*` wrappers never expose those
 * (08 Theming and colour). The All groups picker can start half height; other sheets expand fully.
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
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState =
            rememberModalBottomSheetState(
                skipPartiallyExpanded = initialExpansion == NdSheetExpansion.EXPANDED,
            ),
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColorFor(containerColor),
        sheetMaxWidth = sheetMaxWidth,
        dragHandle = { SheetDragHandle() },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = shouldDismissOnBackPress),
        content = content,
    )
}

/**
 * Material 1.4 attaches its handle's describing semantics to a 32 dp-wide layout, so the
 * on-device ATF audit measures it as a touch target and fails 08's 48 dp minimum (Subscribe
 * journey evidence). This keeps the stock visual on a node that spans a real 48 dp target.
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
