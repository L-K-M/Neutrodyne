// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.desktop

import ch.lkmc.neutrodyne.core.domain.ChapterRepository
import ch.lkmc.neutrodyne.core.domain.PlaySessionStore
import ch.lkmc.neutrodyne.core.domain.PlaybackMediaLookup
import ch.lkmc.neutrodyne.core.domain.PositionStore
import ch.lkmc.neutrodyne.core.domain.QueueRepository
import ch.lkmc.neutrodyne.playback.core.EffectivePlaybackSettings
import ch.lkmc.neutrodyne.playback.core.PlayStarter
import ch.lkmc.neutrodyne.playback.core.PositionSaver
import ch.lkmc.neutrodyne.playback.core.QueueWindowPlanner
import ch.lkmc.neutrodyne.playback.engine.AudioEngine
import ch.lkmc.neutrodyne.playback.engine.SpanCache
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource

/** Construct only after database open. The controller owns these per-player core instances. */
class DesktopPlaybackRuntime(
    internal val starter: PlayStarter,
    internal val planner: QueueWindowPlanner,
    internal val saver: PositionSaver,
    internal val settings: EffectivePlaybackSettings,
    internal val sessions: PlaySessionStore,
    internal val queue: QueueRepository,
    internal val media: PlaybackMediaLookup,
    internal val positions: PositionStore,
    internal val capabilities: YouTubeCapabilitiesSource,
    internal val chapterRepository: ChapterRepository,
    internal val resolver: DesktopEpisodeSourceResolver,
    internal val cache: SpanCache,
    internal val engine: AudioEngine,
)
