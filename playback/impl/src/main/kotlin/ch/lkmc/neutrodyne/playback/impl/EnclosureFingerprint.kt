// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import java.security.MessageDigest

internal object EnclosureFingerprint {
    private const val FINGERPRINT_CHARS = 16
    private const val HEX = "0123456789abcdef"

    fun cacheKey(
        episodeId: Long,
        url: String,
        length: Long?,
    ): String = "ep:$episodeId:${of(url, length)}"

    fun of(
        url: String,
        length: Long?,
    ): String =
        MessageDigest
            .getInstance("SHA-1")
            .digest("$url|${length ?: -1}".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "${HEX[(byte.toInt() and 0xff) ushr 4]}${HEX[byte.toInt() and 0xf]}" }
            .take(FINGERPRINT_CHARS)
}
