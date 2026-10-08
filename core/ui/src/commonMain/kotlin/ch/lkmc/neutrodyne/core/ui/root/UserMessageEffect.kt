// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import ch.lkmc.neutrodyne.core.ui.UserMessage
import ch.lkmc.neutrodyne.core.ui.resolve
import kotlinx.collections.immutable.ImmutableList

/**
 * Drains a screen state's pending [UserMessage]s into the root snackbar, acknowledging each one
 * after dismissal (01: one-shot events are state with acknowledgement). `showSnackbar` suspends
 * until dismissal, so queued messages appear serially.
 */
@Composable
public fun ShowUserMessages(
    messages: ImmutableList<UserMessage>,
    onMessageShown: (Long) -> Unit,
) {
    val snackbar = LocalSnackbarHost.current
    LaunchedEffect(messages) {
        for (message in messages) {
            snackbar.showSnackbar(
                message = message.text.resolve(),
                actionLabel = message.action?.resolve(),
                withDismissAction = message.action != null,
            )
            onMessageShown(message.id)
        }
    }
}
