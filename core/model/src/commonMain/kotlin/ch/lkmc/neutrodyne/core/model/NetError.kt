// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/**
 * The taxonomy every source/network call funnels into (01 Networking baseline); `:core:net`'s
 * `NetErrorClassifier` maps transport exceptions onto it. `:core:model` so other modules can
 * store and show it.
 */
sealed interface NetError {
    /** Network down or unvalidated at the moment of the call (do not burn a refresh try). */
    data object Offline : NetError

    data object Timeout : NetError

    data object DnsFailure : NetError

    data object ConnectionFailed : NetError

    /** Direct LAN HTTP needs the Android Local Network permission (10; sync only). */
    data object LocalNetworkUnsupported : NetError

    data class Tls(
        val kind: TlsKind,
    ) : NetError

    data object Cancelled : NetError

    data class Other(
        val type: String,
    ) : NetError
}

enum class TlsKind { UNTRUSTED_CERTIFICATE, CERTIFICATE_TRANSPARENCY, HANDSHAKE }
