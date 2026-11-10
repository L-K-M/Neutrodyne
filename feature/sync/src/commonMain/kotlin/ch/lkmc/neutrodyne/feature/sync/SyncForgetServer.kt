// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.feature.sync.resources.Res
import ch.lkmc.neutrodyne.feature.sync.resources.sync_forget_failed
import ch.lkmc.neutrodyne.feature.sync.resources.sync_forget_server
import ch.lkmc.neutrodyne.feature.sync.resources.sync_forget_stale
import ch.lkmc.neutrodyne.feature.sync.resources.sync_forget_unexpected
import ch.lkmc.neutrodyne.sync.api.ForgetServerError
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import ch.lkmc.neutrodyne.core.ui.resources.Res as UiRes

/**
 * "Forget server" on the `Reconnect` and `Revoked` cards (08 Settings › Sync): one click on
 * [SyncController.forgetServer], the atomic local-only clear — the stored address, username and
 * the link's bookkeeping go, the library stays, and no request reaches the forgotten server.
 * Only the two disconnected statuses offer it: `Linked` unlinks instead, `LinkUnfinished`
 * resumes its flow and `NotConfigured` has nothing to forget.
 *
 * The action belongs to the exact status it was admitted under: the caller `key()`s this
 * section to the status value, and every dispatch and completion re-checks it — a stale click
 * or a late completion can never clear a newer link or paint a different card. While the call
 * is in flight the button is disabled. An `Outcome.Failure` keeps the stored profile and the
 * actual status (a failed write never reports `NotConfigured`), so the card stays and the typed
 * reason shows with a Retry that re-sends the same call; a success paints nothing — the status
 * flow itself flips to `NotConfigured`, so no fire-and-forget "success" is ever shown.
 */
@Composable
internal fun ColumnScope.ForgetServerAction(
    controller: SyncController,
    admitted: SyncStatus,
) {
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<ForgetFailure?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun stillCurrent(): Boolean = controller.status.value == admitted

    fun run() {
        // A click left over on a dying card must not even dispatch.
        if (busy || !stillCurrent()) return
        scope.launch {
            // Re-check at dispatch: the status can have changed since the click.
            if (busy || !stillCurrent()) return@launch
            busy = true
            failure = null
            val result =
                try {
                    when (val outcome = controller.forgetServer()) {
                        is Outcome.Success -> null
                        is Outcome.Failure -> ForgetFailure.Typed(outcome.error)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (unexpected: Throwable) {
                    ForgetFailure.Unexpected
                }
            if (stillCurrent()) {
                busy = false
                failure = result
            }
        }
    }

    NdTextButton(
        label = stringResource(Res.string.sync_forget_server),
        onClick = { run() },
        enabled = !busy,
    )

    failure?.let { current ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                when (current) {
                    is ForgetFailure.Typed -> {
                        when (current.error) {
                            ForgetServerError.StaleLink -> {
                                stringResource(Res.string.sync_forget_stale)
                            }

                            ForgetServerError.WriteFailed -> {
                                stringResource(Res.string.sync_forget_failed)
                            }
                        }
                    }

                    ForgetFailure.Unexpected -> {
                        stringResource(Res.string.sync_forget_unexpected)
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            NdTextButton(
                label = stringResource(UiRes.string.action_retry),
                onClick = { run() },
                enabled = !busy,
            )
        }
    }
}

/** A failed forget kept for its Retry action; `run` re-sends the same call. */
private sealed interface ForgetFailure {
    data class Typed(
        val error: ForgetServerError,
    ) : ForgetFailure

    data object Unexpected : ForgetFailure
}
