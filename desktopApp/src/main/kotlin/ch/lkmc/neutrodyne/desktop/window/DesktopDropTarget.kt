// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import ch.lkmc.neutrodyne.desktop.DesktopOpenHandler
import ch.lkmc.neutrodyne.desktop.resources.Res
import ch.lkmc.neutrodyne.desktop.resources.drop_to_add
import ch.lkmc.neutrodyne.desktop.resources.drop_to_import
import org.jetbrains.compose.resources.stringResource

/** Accept the transfer at the window boundary; the open handler validates every item. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DesktopDropTarget(
    handler: DesktopOpenHandler,
    content: @Composable () -> Unit,
) {
    var overlay by remember { mutableStateOf<DropOverlay?>(null) }
    val target =
        remember(handler) {
            object : DragAndDropTarget {
                override fun onEntered(event: DragAndDropEvent) {
                    overlay = if (event.dragData() is DragData.FilesList) DropOverlay.IMPORT else DropOverlay.ADD
                }

                override fun onExited(event: DragAndDropEvent) {
                    overlay = null
                }

                override fun onEnded(event: DragAndDropEvent) {
                    overlay = null
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    overlay = null
                    val inputs =
                        runCatching {
                            when (val data = event.dragData()) {
                                is DragData.FilesList -> data.readFiles().take(DesktopOpenHandler.MAX_INPUTS)
                                is DragData.Text -> droppedTextInputs(data.readText())
                                else -> listOf("")
                            }
                        }.getOrElse { listOf("") }
                    handler.submit(inputs)
                    return true
                }
            }
        }
    Box(Modifier.fillMaxSize().dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target)) {
        content()
        overlay?.let { current ->
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(
                        if (current ==
                            DropOverlay.IMPORT
                        ) {
                            Res.string.drop_to_import
                        } else {
                            Res.string.drop_to_add
                        },
                    ),
                )
            }
        }
    }
}

// text/uri-list uses lines and comments; plain browser URL text uses the same path.
internal fun droppedTextInputs(text: String): List<String> =
    text
        .lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .take(DesktopOpenHandler.MAX_INPUTS)
        .toList()
        .ifEmpty { listOf("") }

private enum class DropOverlay { IMPORT, ADD }
