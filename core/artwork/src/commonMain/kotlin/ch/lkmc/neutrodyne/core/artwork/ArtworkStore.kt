// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.artwork

import ch.lkmc.neutrodyne.core.model.ArtworkRef
import okio.Path

/**
 * The pinned-artwork index (08 ArtworkStore). References are data, not counters: a key is
 * referenced while a podcast or completed download uses it. `pin`/`unpin` never write reference
 * state — they schedule the sync run that fetches or drops the file.
 */
public interface ArtworkStore {
    /** The on-disk file of [key] if present (`okio.Path`; call on IO). */
    public fun pinnedFile(key: String): Path?

    /** Request: make sure this key is stored; idempotent. */
    public fun pin(
        ref: ArtworkRef,
        reason: PinReason,
        ownerId: Long,
    )

    /** Hint: delete the key if nothing references it any more. */
    public fun unpin(
        key: String,
        reason: PinReason,
        ownerId: Long,
    )

    /**
     * Android: `content://{applicationId}.artwork/{key}?v={version}`; desktop: the `file:` URI of
     * the key's path (MPRIS `mpris:artUrl`).
     */
    public fun contentUri(
        key: String,
        version: Int,
    ): String

    /** In-memory index lookup, any thread. */
    public fun isPinned(key: String): Boolean

    /** Index lookup without `exists()`, for Coil's [ArtworkRefMapper] on the main thread. */
    public fun pinnedPath(key: String): Path?

    /** 02's garbage query; called by `db-maintenance` (02 step 4). */
    public suspend fun collectGarbage(): Int
}

/** Sync-run priority of a pin/unpin request (08): SUBSCRIPTION and DOWNLOAD outrank the rest. */
public enum class PinReason {
    SUBSCRIPTION,
    DOWNLOAD,
    MONOGRAM,
    GROUP_MOSAIC,
}
