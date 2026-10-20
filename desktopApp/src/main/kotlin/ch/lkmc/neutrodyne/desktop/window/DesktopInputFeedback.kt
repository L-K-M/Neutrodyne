// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import ch.lkmc.neutrodyne.core.domain.ImportError
import ch.lkmc.neutrodyne.desktop.DesktopOpenHandler
import ch.lkmc.neutrodyne.feature.importexport.ImportFailureDialog
import kotlinx.coroutines.CompletableDeferred

/** Composed inside the ready root, after its navigator is attached. */
@Composable
internal fun DesktopInputFeedback(handler: DesktopOpenHandler) {
    var failure by remember { mutableStateOf<ImportError?>(null) }
    var dismissed by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }
    LaunchedEffect(handler) {
        withFrameNanos { }
        handler.afterFirstFrame()
        handler.failures.collect {
            val signal = CompletableDeferred<Unit>()
            failure = it
            dismissed = signal
            signal.await()
            failure = null
            dismissed = null
        }
    }
    failure?.let { ImportFailureDialog(it, onDismiss = { dismissed?.complete(Unit) }) }
}
