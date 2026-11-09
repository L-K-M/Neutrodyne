// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.runtime.Composable
import ch.lkmc.neutrodyne.feature.sync.resources.Res
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_app_too_old
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_certificate
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_clock_skew
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_device_gone
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_insecure_transport
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_invalid_code
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_invite_invalid
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_link_declined
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_local_network
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_merge_failed
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_not_a_server
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_offline
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_quota
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_revoked
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_server
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_server_too_old
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_unreachable
import ch.lkmc.neutrodyne.feature.sync.resources.sync_error_wrong_password
import ch.lkmc.neutrodyne.sync.api.SyncProblem
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import org.jetbrains.compose.resources.stringResource

/**
 * The identity a sync operation belongs to: the (server, account, device) triple of the `Linked`
 * status it started under. Async screen state is keyed to this — never to the whole
 * [SyncStatus.Linked] value, whose `activity`/`lastSyncAtMs` change on every SSE event or round
 * and would otherwise cancel valid in-flight operations (08 Sync screens, 10 Errors).
 */
internal data class SyncIdentity(
    val serverUrl: String,
    val accountId: String,
    val deviceId: String,
)

/** The identity of a `Linked` status, or `null` while the device is not linked. */
internal fun SyncStatus.identity(): SyncIdentity? =
    (this as? SyncStatus.Linked)?.let { SyncIdentity(it.serverUrl, it.accountId, it.deviceId) }

/** The configured server URL of any status that still knows one, for wording and the web link. */
internal fun SyncStatus.serverUrl(): String? =
    when (this) {
        is SyncStatus.Linked -> serverUrl
        is SyncStatus.Reconnect -> serverUrl
        is SyncStatus.LinkUnfinished -> serverUrl
        is SyncStatus.Revoked -> serverUrl
        SyncStatus.NotConfigured -> null
    }

/**
 * `spaces and hyphens ignored, case-insensitive`, as the server normalises user codes (10 Link
 * methods); `:sync:impl` re-normalises before the request, so this only needs to match it.
 */
internal fun normaliseUserCode(code: String): String = code.filterNot { it == ' ' || it == '-' }.uppercase()

/** `SyncProblem` → the wording of 08's "Sync status and problem texts" and link-flow failures. */
@Composable
internal fun problemText(
    error: SyncProblem,
    host: String,
): String =
    when (error) {
        SyncProblem.Offline -> {
            stringResource(Res.string.sync_error_offline)
        }

        is SyncProblem.Unreachable -> {
            stringResource(Res.string.sync_error_unreachable, host)
        }

        SyncProblem.CertificateRejected -> {
            stringResource(Res.string.sync_error_certificate, host)
        }

        SyncProblem.Revoked -> {
            stringResource(Res.string.sync_error_revoked, host)
        }

        SyncProblem.AppTooOld -> {
            stringResource(Res.string.sync_error_app_too_old, host)
        }

        SyncProblem.ServerTooOld -> {
            stringResource(Res.string.sync_error_server_too_old, host)
        }

        SyncProblem.ClockSkew -> {
            stringResource(Res.string.sync_error_clock_skew)
        }

        SyncProblem.QuotaReached -> {
            stringResource(Res.string.sync_error_quota, host)
        }

        SyncProblem.LocalNetworkPermissionDenied -> {
            stringResource(Res.string.sync_error_local_network, host)
        }

        SyncProblem.InsecureTransport -> {
            stringResource(Res.string.sync_error_insecure_transport)
        }

        SyncProblem.WrongPassword -> {
            stringResource(Res.string.sync_error_wrong_password)
        }

        SyncProblem.InvalidCode -> {
            stringResource(Res.string.sync_error_invalid_code)
        }

        SyncProblem.LinkDeclined -> {
            stringResource(Res.string.sync_error_link_declined)
        }

        SyncProblem.InviteInvalid -> {
            stringResource(Res.string.sync_error_invite_invalid)
        }

        SyncProblem.MergeFailed -> {
            stringResource(Res.string.sync_error_merge_failed)
        }

        SyncProblem.DeviceGone -> {
            stringResource(Res.string.sync_error_device_gone)
        }

        is SyncProblem.Server -> {
            if (error.status == 404) {
                stringResource(Res.string.sync_error_not_a_server, host)
            } else {
                stringResource(Res.string.sync_error_server, host, error.status)
            }
        }
    }

internal fun hostOf(url: String): String = url.removePrefix("https://").removePrefix("http://").substringBefore('/')
