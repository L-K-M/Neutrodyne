// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.api

/**
 * The problem kinds of 10 Error handling and backoff, mapped to 08's status texts. Pure data:
 * [Unreachable] carries a stable [code] (a `NetErrorClassifier` code) the UI can word.
 */
sealed interface SyncProblem {
    data object Offline : SyncProblem

    data class Unreachable(
        val code: String,
    ) : SyncProblem

    data object CertificateRejected : SyncProblem

    data object Revoked : SyncProblem

    data object AppTooOld : SyncProblem

    data object ServerTooOld : SyncProblem

    data object ClockSkew : SyncProblem

    data object QuotaReached : SyncProblem

    data object LocalNetworkPermissionDenied : SyncProblem

    /** Plain `http://` to a public address is refused before any request (10 TLS stance). */
    data object InsecureTransport : SyncProblem

    /** The username or password was wrong (`POST /api/v1/auth/login`, 401). */
    data object WrongPassword : SyncProblem

    /** A link code is unknown, expired or already used (10 Link methods, 401/400). */
    data object InvalidCode : SyncProblem

    /** Another device declined the link request (`access_denied`). */
    data object LinkDeclined : SyncProblem

    /** An invite code was used or expired (`POST /api/v1/auth/invite/redeem`, 401). */
    data object InviteInvalid : SyncProblem

    /** The first-link merge failed; the library is unchanged and linking stays unfinished. */
    data object MergeFailed : SyncProblem

    /** The addressed device row is already gone (`PATCH`/`DELETE /devices/{id}` → 404; 10 Errors: refresh the device list). */
    data object DeviceGone : SyncProblem

    /** A server response the client could not interpret (`Server(status)` in diagnostics). */
    data class Server(
        val status: Int,
    ) : SyncProblem
}
