// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.suggested_groups_open
import ch.lkmc.neutrodyne.core.ui.resources.suggested_groups_partial
import ch.lkmc.neutrodyne.core.ui.resources.suggested_groups_result
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
public fun SuggestedGroupsFeedback(
    created: Int,
    failed: Int,
    onOpen: (() -> Unit)?,
    onConsume: () -> Unit,
) {
    val host = LocalSnackbarHost.current
    val message =
        if (failed == 0) {
            pluralStringResource(Res.plurals.suggested_groups_result, created, created)
        } else {
            stringResource(Res.string.suggested_groups_partial, created, failed)
        }
    val open = stringResource(Res.string.suggested_groups_open)
    val openAction by rememberUpdatedState(onOpen)
    val consumed by rememberUpdatedState(onConsume)
    LaunchedEffect(Unit) {
        try {
            val result =
                host.showSnackbar(
                    message,
                    actionLabel = if (openAction != null) open else null,
                    duration = if (openAction != null) SnackbarDuration.Long else SnackbarDuration.Short,
                )
            if (result == SnackbarResult.ActionPerformed) openAction?.invoke()
        } finally {
            consumed()
        }
    }
}
