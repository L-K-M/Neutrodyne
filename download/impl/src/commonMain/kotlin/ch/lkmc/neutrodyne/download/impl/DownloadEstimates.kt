// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

/** Ignore placeholder lengths and use 128 kbit/s for a duration hint, in milliseconds. */
internal fun downloadEstimate(
    length: Long?,
    durationMs: Long?,
): Long? =
    length?.takeIf { it >= MIN_ENCLOSURE_BYTES }
        ?: durationMs?.takeIf { it > 0 }?.let {
            it.coerceAtMost(Long.MAX_VALUE / BYTES_PER_MILLISECOND) * BYTES_PER_MILLISECOND
        }

internal const val UNKNOWN_DOWNLOAD_BYTES = 150L * 1024 * 1024
private const val MIN_ENCLOSURE_BYTES = 100_000L
private const val BYTES_PER_MILLISECOND = 16L
