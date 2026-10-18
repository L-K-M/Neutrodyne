// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.api

/**
 * Thread-safe, synchronous, non-blocking lookup from an in-memory completed-file index (07).
 * Null means no usable local file; never query storage/network here. No files are produced until
 * M6. A returned URI is a hint, not a guarantee against subsequent deletion.
 */
fun interface LocalMediaIndex {
    fun localUriOrNull(episodeId: Long): String?
}
