// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.designsystem.components.NdButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdProgress
import ch.lkmc.neutrodyne.core.designsystem.components.NdTextButton
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.ui.FeedDates
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.resources.action_continue
import ch.lkmc.neutrodyne.core.ui.root.LocalRootScope
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.feature.sync.resources.Res
import ch.lkmc.neutrodyne.feature.sync.resources.sync_approve_code_hint
import ch.lkmc.neutrodyne.feature.sync.resources.sync_approve_code_label
import ch.lkmc.neutrodyne.feature.sync.resources.sync_approve_declined
import ch.lkmc.neutrodyne.feature.sync.resources.sync_approve_dont_link
import ch.lkmc.neutrodyne.feature.sync.resources.sync_approve_link
import ch.lkmc.neutrodyne.feature.sync.resources.sync_approve_link_question
import ch.lkmc.neutrodyne.feature.sync.resources.sync_approve_linked
import ch.lkmc.neutrodyne.feature.sync.resources.sync_approve_other_network
import ch.lkmc.neutrodyne.feature.sync.resources.sync_approve_pending_detail
import ch.lkmc.neutrodyne.feature.sync.resources.sync_approve_purpose_device
import ch.lkmc.neutrodyne.feature.sync.resources.sync_approve_purpose_web
import ch.lkmc.neutrodyne.feature.sync.resources.sync_approve_rate_limited
import ch.lkmc.neutrodyne.feature.sync.resources.sync_approve_sign_in_question
import ch.lkmc.neutrodyne.feature.sync.resources.sync_link_another
import ch.lkmc.neutrodyne.sync.api.LinkPurpose
import ch.lkmc.neutrodyne.sync.api.PendingDevice
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.api.SyncProblem
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import ch.lkmc.neutrodyne.core.ui.resources.Res as UiRes

/**
 * The "Link another device" sheet (08 Link another device) on `SyncApproveKey`. The code field is
 * normalised the way the server does it — case-insensitive, spaces and hyphens ignored — and only
 * a normalised eight-character code enables lookup. `approveLink` is a lookup only: it names the
 * pending device, which the user then links or declines explicitly with `confirmLink`; the code
 * alone never links anything. Editing the field discards the pending lookup, so a late result can
 * never confirm a device the visible code no longer names.
 *
 * All async state is owned by the [SyncIdentity] (server, account, device) it started under: a
 * status refresh that only updates `activity`/`lastSyncAtMs` keeps the sheet's work alive, while
 * an unlink or relink pops the keyed body — and every completion additionally checks the current
 * identity before writing, so a stale approval can never paint over a different account.
 */
@Composable
fun SyncApproveSheet(
    controller: SyncController,
    initialCode: String,
    modifier: Modifier = Modifier,
) {
    val status by controller.status.collectAsStateWithLifecycle()
    // The body is keyed to the (server, account, device) identity: an `activity`/`lastSyncAtMs`
    // update recomposes without touching it, while an unlink or relink pops it — scope, in-flight
    // calls and the pending approval die with it, so a stale approval can never confirm against a
    // different account or device.
    key(status.identity()) {
        SyncApproveBody(controller, status.identity(), hostOf(status.serverUrl().orEmpty()), initialCode, modifier)
    }
}

@Composable
private fun SyncApproveBody(
    controller: SyncController,
    identity: SyncIdentity?,
    host: String,
    initialCode: String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // The success snackbar outlives the sheet's own pop: it runs on the root scope (10's "work
    // that must outlive a route"), while every screen operation stays on the body scope above.
    val rootScope = LocalRootScope.current
    val snackbar = LocalSnackbarHost.current
    val clock = LocalUiClock.current
    val navigator = LocalAppNavigator.current

    var code by remember { mutableStateOf(initialCode) }
    var pendingCode by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PendingDevice?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<SyncProblem?>(null) }

    fun stillCurrent(): Boolean = controller.status.value.identity() == identity

    fun lookup() {
        if (identity == null) return
        val normalised = normaliseUserCode(code)
        if (normalised.length != USER_CODE_LENGTH) return
        scope.launch {
            busy = true
            error = null
            when (val outcome = controller.approveLink(normalised)) {
                is Outcome.Success -> {
                    if (stillCurrent()) {
                        busy = false
                        // A late result only names the device when the field still holds the code
                        // it was looked up with — an edit since the request dropped it.
                        if (normaliseUserCode(code) == normalised) {
                            pending = outcome.value
                            pendingCode = normalised
                        }
                    }
                }

                is Outcome.Failure -> {
                    if (stillCurrent()) {
                        busy = false
                        if (normaliseUserCode(code) == normalised) {
                            error = outcome.error
                        }
                    }
                }
            }
        }
    }

    fun confirm(
        approve: Boolean,
        successMessage: String,
    ) {
        if (identity == null) return
        val codeFor = pendingCode ?: return
        scope.launch {
            busy = true
            error = null
            when (val outcome = controller.confirmLink(codeFor, approve)) {
                is Outcome.Success -> {
                    if (stillCurrent() && pendingCode == codeFor) {
                        rootScope.launch { snackbar.showSnackbar(successMessage) }
                        navigator.pop()
                    } else {
                        busy = false
                    }
                }

                is Outcome.Failure -> {
                    if (stillCurrent()) {
                        busy = false
                        error = outcome.error
                    }
                }
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(Res.string.sync_link_another), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = code,
            onValueChange = {
                code = it
                pending = null
                pendingCode = null
                error = null
            },
            label = { Text(stringResource(Res.string.sync_approve_code_label)) },
            placeholder = { Text(stringResource(Res.string.sync_approve_code_hint)) },
            isError = error != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Text(
                approveErrorText(it, host),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        val shown = pending
        if (shown == null) {
            if (busy) {
                NdProgress.Linear(progress = null, modifier = Modifier.fillMaxWidth())
            }
            NdButton(
                label = stringResource(UiRes.string.action_continue),
                onClick = ::lookup,
                enabled = !busy && normaliseUserCode(code).length == USER_CODE_LENGTH,
            )
        } else {
            Text(
                stringResource(
                    when (shown.purpose) {
                        LinkPurpose.DEVICE -> Res.string.sync_approve_link_question
                        LinkPurpose.WEB -> Res.string.sync_approve_sign_in_question
                    },
                    shown.deviceName,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(
                    Res.string.sync_approve_pending_detail,
                    shown.platform,
                    shown.appVersion,
                    FeedDates.relative(shown.requestedAtMs, clock.now()).asString(),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            // The grant in plain words (10 Link codes): a device receives the whole library, a
            // `web` request only signs a browser in — never rendered as a device link.
            Text(
                text =
                    when (shown.purpose) {
                        LinkPurpose.DEVICE -> {
                            stringResource(Res.string.sync_approve_purpose_device, shown.deviceName)
                        }

                        LinkPurpose.WEB -> {
                            stringResource(Res.string.sync_approve_purpose_web)
                        }
                    },
                style = MaterialTheme.typography.bodyMedium,
            )
            // `sameNetwork == false` is the consent-phishing shape (10 Link codes, RFC 8628
            // §5.4): the request did not come from the approver's client IP.
            if (!shown.sameNetwork) {
                Text(
                    stringResource(Res.string.sync_approve_other_network),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (busy) {
                NdProgress.Linear(progress = null, modifier = Modifier.fillMaxWidth())
            }
            val linkedMessage = stringResource(Res.string.sync_approve_linked, shown.deviceName)
            val declinedMessage = stringResource(Res.string.sync_approve_declined)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NdTextButton(
                    label = stringResource(Res.string.sync_approve_dont_link),
                    onClick = { confirm(false, declinedMessage) },
                    enabled = !busy,
                )
                NdButton(
                    label = stringResource(Res.string.sync_approve_link),
                    onClick = { confirm(true, linkedMessage) },
                    enabled = !busy,
                )
            }
        }
    }
}

@Composable
private fun approveErrorText(
    error: SyncProblem,
    host: String,
): String =
    if (error is SyncProblem.Server && error.status == HTTP_TOO_MANY_REQUESTS) {
        stringResource(Res.string.sync_approve_rate_limited)
    } else {
        problemText(error, host)
    }

private const val USER_CODE_LENGTH = 8
private const val HTTP_TOO_MANY_REQUESTS = 429
