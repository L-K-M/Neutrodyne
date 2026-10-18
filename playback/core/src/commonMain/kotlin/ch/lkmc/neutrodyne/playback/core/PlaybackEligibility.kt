// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.playback.api.UnplayableReason
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilities

/** Shared structural gates. Offline/metered policy does not remove entries from the window. */
internal fun PlaybackMediaInfo.refusal(capabilities: YouTubeCapabilities): UnplayableReason? {
    val youtube = sourceType != SourceType.RSS
    if ((!youtube && enclosureUrl.isNullOrBlank()) || (youtube && externalMediaId.isNullOrBlank())) {
        return UnplayableReason.NoMedia
    }
    if (youtube && !capabilities.inAppPlayback) {
        return UnplayableReason.YouTubeExternal(capabilities.externalReason ?: ExternalReason.NOT_YET_AVAILABLE)
    }
    if (availability != Availability.AVAILABLE) return UnplayableReason.YouTube(availability)

    return null
}
