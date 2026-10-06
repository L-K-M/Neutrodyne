// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneShapes

/**
 * `ModalBottomSheet` with the Neutrodyne sheet top corners (28 dp, `NeutrodyneShapes.Sheet`). On
 * desktop this is window-backed (Compose `Dialog`), so navigation-level sheets render above the
 * expanded player — that is what the shared `NdSheetSceneStrategy` relies on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun NdModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    shape: Shape = NeutrodyneShapes.Sheet,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    shouldDismissOnBackPress: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColorFor(containerColor),
        sheetMaxWidth = sheetMaxWidth,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = shouldDismissOnBackPress),
        content = content,
    )
}

/** Convenience: the partially-expanded detent is never used for app sheets (08 Player sheet). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun rememberNdSheetState(): SheetState =
    rememberModalBottomSheetState(skipPartiallyExpanded = true)
