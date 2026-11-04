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

    /** A server response the client could not interpret (`Server(status)` in diagnostics). */
    data class Server(
        val status: Int,
    ) : SyncProblem
}
