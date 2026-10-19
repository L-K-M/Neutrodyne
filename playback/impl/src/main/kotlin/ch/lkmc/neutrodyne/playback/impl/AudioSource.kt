// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.core.net.toUri
import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo

/** Keep URL and length together: a muxed response must never satisfy an alternate's cache key. */
internal data class AudioSource(
    val url: String,
    val length: Long?,
)

internal fun PlaybackMediaInfo.audioSource(): AudioSource? {
    if (isVideo) {
        audioAlternate?.takeIf { it.type.startsWith("audio/", ignoreCase = true) && usableRemoteUrl(it.url) }?.let {
            return AudioSource(it.url, it.length)
        }
    }
    return enclosureUrl?.takeIf(::usableRemoteUrl)?.let { AudioSource(it, enclosureLength) }
}

private fun usableRemoteUrl(value: String): Boolean =
    try {
        val uri = value.toUri()
        uri.scheme?.lowercase() in REMOTE_SCHEMES && !uri.host.isNullOrBlank() &&
            (uri.port == -1 || uri.port in 1..MAX_REMOTE_PORT)
    } catch (_: IllegalArgumentException) {
        false
    }

private const val MAX_REMOTE_PORT = 65_535
private val REMOTE_SCHEMES = setOf("http", "https")
