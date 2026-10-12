// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.artwork

import okio.Path

/**
 * The platform side of `ArtworkStore.contentUri` (08): Android builds the `ArtworkProvider`
 * `content://` authority from `context.packageName`, the desktop a `file:` URI of the pinned
 * [Path] (MPRIS `mpris:artUrl`).
 */
public interface ArtworkContentUris {
    /** The external URI for [key]@[version]; [path] is the pinned file when known. */
    public fun of(
        key: String,
        version: Int,
        path: Path?,
    ): String
}
