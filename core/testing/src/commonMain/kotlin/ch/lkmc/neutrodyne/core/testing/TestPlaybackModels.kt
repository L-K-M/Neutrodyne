// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import ch.lkmc.neutrodyne.playback.api.PositionSnapshot

/** Platform facts for shared feature tests, without platform SDK dependencies. */
class FakePlatformInfo(
    override val kind: PlatformKind = PlatformKind.DESKTOP,
) : PlatformInfo {
    override val userAgentPlatform: String = "test"
    override val androidSdkInt: Int? = null
    override val regionCode: String = "DE"
}

/** A loaded, advancing item; tests use copy for paused, buffering and transition snapshots. */
fun testNowPlaying(id: Long = 1): NowPlaying {
    val row = testEpisodeRow(id)
    return NowPlaying(
        id,
        row.podcastId,
        row.title,
        row.podcastTitle,
        row.artwork,
        row.sourceType,
        isVideo = false,
        phase = PlayerPhase.READY,
        isPlaying = true,
        playWhenReady = true,
        position = PositionSnapshot(id, 10_000, 20_000, row.durationMs, 1f, true, 0),
        hasNext = true,
        stream = null,
        issue = null,
        context = null,
    )
}

/** Default desktop facts for shared feature tests. */
val testPlatformInfo: PlatformInfo = FakePlatformInfo()
