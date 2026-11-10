// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

/**
 * The player's `m:ss` clock text: minutes never roll into hours ("125:05") and negative input
 * clamps to "0:00". Shared by the player surfaces and the remote-session card's position.
 */
public fun playerTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / MILLIS_PER_SECOND
    val minutes = seconds / SECONDS_PER_MINUTE
    return "$minutes:${(seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
