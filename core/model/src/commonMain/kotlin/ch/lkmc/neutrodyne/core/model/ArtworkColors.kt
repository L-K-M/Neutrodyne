// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/**
 * Persisted metadata for the returned key/version, which can be the podcast fallback key.
 * A non-null [avgArgb] marks completed extraction, even when [seedArgb] is null (monochrome).
 * No acceptable alpha >= 128 pixels completes with seed null and average transparent zero.
 * Both null means unprocessed; absence of the entire value means no available colour row.
 */
data class ArtworkColors(
    val key: String,
    val version: Int,
    val seedArgb: Int?,
    val avgArgb: Int?,
)
