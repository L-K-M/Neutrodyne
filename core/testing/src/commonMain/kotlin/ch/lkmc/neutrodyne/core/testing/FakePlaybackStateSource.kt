// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.model.CurrentChapter
import ch.lkmc.neutrodyne.core.model.EffectivePlayback
import ch.lkmc.neutrodyne.core.model.SleepTimerState
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.PlaybackEvent
import ch.lkmc.neutrodyne.playback.api.PlaybackStateSource
import ch.lkmc.neutrodyne.playback.api.PositionSnapshot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Test-owned mutable state; events/ticks have no replay. Confine mutation to the test dispatcher. */
class FakePlaybackStateSource : PlaybackStateSource {
    override val sleepTimer = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    override val currentChapter = MutableStateFlow<CurrentChapter?>(null)
    override val pendingMeteredConsent = MutableStateFlow<PlayResult.NeedsMeteredConsent?>(null)
    override val nowPlaying = MutableStateFlow<NowPlaying?>(null)
    override val effectivePlayback = MutableStateFlow<EffectivePlayback?>(null)
    override val positionTicks = MutableSharedFlow<PositionSnapshot>(extraBufferCapacity = 1)
    override val events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 1)
}
