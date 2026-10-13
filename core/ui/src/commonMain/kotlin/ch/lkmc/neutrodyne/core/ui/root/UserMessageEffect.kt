// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import ch.lkmc.neutrodyne.core.ui.UserMessage
import ch.lkmc.neutrodyne.core.ui.resolve
import kotlinx.collections.immutable.ImmutableList

/**
 * Drains a screen state's pending [UserMessage]s into the root snackbar, acknowledging each one
 * after dismissal (01: one-shot events are state with acknowledgement). `showSnackbar` suspends
 * until dismissal, so queued messages appear serially. An action tap is reported back through
 * [onMessageAction] by message id — the posting ViewModel owns the id-to-action mapping (01's
 * `onMessageAction`, 08's Undo catalogue).
 */
@Composable
public fun ShowUserMessages(
    messages: ImmutableList<UserMessage>,
    onAcknowledgeMessage: (Long) -> Unit,
    onMessageAction: (Long) -> Unit = {},
) {
    val snackbar = LocalSnackbarHost.current
    // Keep the current callbacks without restarting the visible snackbar.
    val acknowledge by rememberUpdatedState(onAcknowledgeMessage)
    val action by rememberUpdatedState(onMessageAction)

    // Key on the head message's id, not the list: appending a pending message must not cancel
    // and replay the visible snackbar. When the head is acknowledged and removed, the new head's
    // id launches the next show.
    LaunchedEffect(messages.firstOrNull()?.id) {
        val message = messages.firstOrNull() ?: return@LaunchedEffect
        val result =
            snackbar.showSnackbar(
                message = message.text.resolve(),
                actionLabel = message.action?.resolve(),
                withDismissAction = message.action != null,
            )
        if (result == SnackbarResult.ActionPerformed) action(message.id)
        acknowledge(message.id)
    }
}
