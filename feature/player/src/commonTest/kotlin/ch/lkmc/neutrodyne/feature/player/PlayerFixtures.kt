// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import ch.lkmc.neutrodyne.playback.api.PositionSnapshot
import ch.lkmc.neutrodyne.playback.api.StreamKind

internal fun samplePosition(
    id: Long = 1,
    advancing: Boolean = false,
) = PositionSnapshot(id, 12_000, 20_000, 60_000, 1f, advancing, 1_000)

internal fun sampleNow(
    id: Long = 1,
    playing: Boolean = false,
) = NowPlaying(
    episodeId = id,
    podcastId = 2,
    title = "An episode",
    podcastTitle = "A podcast",
    artwork = ArtworkRef("m-podcast", null, 0),
    sourceType = SourceType.RSS,
    isVideo = false,
    phase = PlayerPhase.READY,
    isPlaying = playing,
    playWhenReady = playing,
    position = samplePosition(id, playing),
    hasNext = true,
    stream = StreamKind.STREAM,
    issue = null,
    context = null,
)
