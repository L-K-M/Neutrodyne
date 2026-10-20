// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import ch.lkmc.neutrodyne.playback.api.PlayResult

/**
 * Adapter gate after shared no-media/capability/availability/offline checks, before any write.
 * Player-thread confined, main-safe suspend; null admits, otherwise return a refusal, never Started.
 * localUri is the current LocalMediaIndex hint, letting the metered gate bypass usable local media.
 * Cancellation propagates. Unexpected failures are mapped by PlayStarter to ServiceUnavailable.
 */
fun interface StartGate {
    suspend fun check(
        item: PlaybackMediaInfo,
        localUri: String?,
    ): PlayResult?
}
