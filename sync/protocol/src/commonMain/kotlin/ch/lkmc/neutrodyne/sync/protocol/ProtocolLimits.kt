// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.protocol

object ProtocolLimits {
    const val MAX_CHANGES = 1_000
    const val MAX_RECORDS = 1_000
    const val MAX_REQUEST_BYTES = 2L shl 20
    const val MAX_DECOMPRESSED_BYTES = 16L shl 20
    const val MAX_RESPONSE_DECOMPRESSED_BYTES = 32L shl 20
    const val MAX_PAGE_BYTES = 8L shl 20
    const val MAX_JSON_DEPTH = 16
    const val MAX_STRING = 4_096
    const val MAX_URL = 4_096
    const val MAX_TITLE = 1_024
    const val MAX_IDENTITY_KEY = 2_048
    const val MAX_SETTING_KEY = 64
    const val MAX_FIELDS_PER_RECORD = 64
    const val MAX_GROW_SET = 64
    const val MAX_ORDER_KEY = 64
    const val MAX_REDIRECT_HOPS = 8
    const val MAX_SKEW_MS = 300_000L
    const val MIN_CLOCK_MS = 1_577_836_800_000L
    const val MAX_DEVICE_NAME = 64
    const val MAX_USERNAME = 40
}

enum class SyncErrorCode(
    val wire: String,
) {
    PROTOCOL_TOO_OLD("protocol_too_old"),
    CURSOR_EXPIRED("cursor_expired"),
    UNAUTHORIZED("unauthorized"),
    FORBIDDEN("forbidden"),
    RATE_LIMITED("rate_limited"),
    PAYLOAD_TOO_LARGE("payload_too_large"),
    INVALID("invalid"),
    NOT_FOUND("not_found"),
    CLOCK_SKEW("clock_skew"),
    QUOTA("quota"),
    UNKNOWN_COLLECTION("unknown_collection"),
    INSECURE_TRANSPORT("insecure_transport"),
}

/** Safe to surface in diagnostics: messages identify the rule, never the untrusted payload. */
class ProtocolException(
    val code: SyncErrorCode,
    message: String,
) : IllegalArgumentException(message)

internal fun invalid(message: String): Nothing = throw ProtocolException(SyncErrorCode.INVALID, message)

/** Counts UTF-8 without allocating an attacker-sized byte array. */
internal fun fitsUtf8(
    text: String,
    cap: Int,
): Boolean {
    if (text.length > cap) return false
    var bytes = 0
    var index = 0
    while (index < text.length) {
        val char = text[index++]
        bytes +=
            when {
                char.code < 0x80 -> {
                    1
                }

                char.code < 0x800 -> {
                    2
                }

                char.isHighSurrogate() && index < text.length && text[index].isLowSurrogate() -> {
                    index++
                    4
                }

                else -> {
                    3
                }
            }
        if (bytes > cap) return false
    }
    return true
}
