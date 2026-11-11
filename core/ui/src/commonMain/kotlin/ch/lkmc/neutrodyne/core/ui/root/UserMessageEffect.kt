// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.root

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import ch.lkmc.neutrodyne.core.ui.UserMessage
import ch.lkmc.neutrodyne.core.ui.resolve
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
        val result = snackbar.showUserMessage(message)
        if (result == SnackbarResult.ActionPerformed) action(message.id)
        acknowledge(message.id)
    }
}

/**
 * Shows [message] on the root host. 08's duration rule: `Long` (10 s) for anything with an
 * action (the Undo catalogue), `Short` for plain confirmations — Material3's default of
 * `Indefinite` whenever an actionLabel is set would hold the one serial host until the user
 * dismisses it. An explicit duration starts only once this message owns the host; time spent
 * waiting behind another snackbar does not shorten its action window.
 */
internal suspend fun SnackbarHostState.showUserMessage(message: UserMessage): SnackbarResult {
    val durationMs = message.durationMs
    if (durationMs != null && durationMs <= 0) return SnackbarResult.Dismissed
    val text = message.text.resolve()
    val label = message.action?.resolve()
    if (durationMs == null) {
        return showSnackbar(
            message = text,
            actionLabel = label,
            withDismissAction = label != null,
            duration = if (label != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
    }

    val visuals =
        object : SnackbarVisuals {
            override val message = text
            override val actionLabel = label
            override val withDismissAction = label != null
            override val duration = SnackbarDuration.Indefinite
        }
    return coroutineScope {
        val expiry =
            launch {
                val displayed =
                    snapshotFlow { currentSnackbarData }
                        .filterNotNull()
                        .first { it.visuals === visuals }
                delay(durationMs)
                displayed.dismiss()
            }
        try {
            showSnackbar(visuals)
        } finally {
            expiry.cancel()
        }
    }
}
