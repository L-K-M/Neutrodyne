// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.shell

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a second launch sends to the owner (11 Single instance and handshake): its launch inputs
 * and working directory. `token` is the owner's 256-bit token read from `instance.token`.
 */
@Serializable
data class HandoffRequest(
    val v: Int = PROTOCOL_VERSION,
    val token: String,
    val args: List<String>,
    val cwd: String,
    val activate: Boolean = true,
) {
    companion object {
        const val PROTOCOL_VERSION = 1

        /** 11 Links and files from the OS: ≤ 20 inputs per hand-off, each ≤ 4 KiB. */
        const val MAX_ARGS = 20
        const val MAX_ARG_CHARS = 4 * 1024

        /** 11 Single instance and handshake: one JSON line ≤ 64 KiB. */
        const val MAX_LINE_BYTES = 64 * 1024

        /**
         * Builds a request from raw launch inputs, enforcing the caps: truncate each input, drop
         * from the end while the serialised line would not fit (halving `cwd` last — a giant
         * working directory is the sender's oddity, not a launch input).
         */
        fun fromLaunchArgs(
            token: String,
            args: List<String>,
            cwd: String,
            activate: Boolean = true,
            json: Json = SHELL_JSON,
        ): HandoffRequest {
            var request =
                HandoffRequest(
                    token = token,
                    args =
                        args
                            .asSequence()
                            .take(MAX_ARGS)
                            .map { it.take(MAX_ARG_CHARS) }
                            .toList(),
                    cwd = cwd,
                    activate = activate,
                )
            while (request.serialisedBytes(json) + 1 > MAX_LINE_BYTES) {
                request =
                    when {
                        request.args.isNotEmpty() -> request.copy(args = request.args.dropLast(1))
                        request.cwd.isNotEmpty() -> request.copy(cwd = request.cwd.take(request.cwd.length / 2))
                        else -> return request // token alone exceeds the cap: it cannot happen with a 43-char token
                    }
            }
            return request
        }
    }
}

/** The owner's answer; `ok = false` is reserved for future refusals. */
@Serializable
data class HandoffResponse(
    val ok: Boolean,
    val pid: Long,
    val versionName: String,
)

/** Whether the owner accepted a hand-off. */
sealed interface HandoffOutcome {
    data object Delivered : HandoffOutcome

    /** No port file, a refused connection or no answer — after [InstanceHandshake]'s retries. */
    data object NoAnswer : HandoffOutcome
}

private fun HandoffRequest.serialisedBytes(json: Json): Int =
    json.encodeToString(HandoffRequest.serializer(), this).toByteArray(Charsets.UTF_8).size
