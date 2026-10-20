// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.playback

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.domain.ContextEligibility
import ch.lkmc.neutrodyne.core.domain.PlayContextResolver
import ch.lkmc.neutrodyne.core.domain.PlaybackMediaLookup
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.playback.api.PlaybackMaintenance
import ch.lkmc.neutrodyne.playback.api.PlaybackStateSource
import ch.lkmc.neutrodyne.playback.api.UnavailablePlaybackController
import ch.lkmc.neutrodyne.playback.api.UnplayableReason
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** MD1 replaces the desktop unavailable binding. Resolving it never touches a database. */
@SingleIn(AppScope::class)
@Inject
internal class DesktopUnavailablePlayback(
    private val opener: DatabaseOpener,
    private val media: Provider<PlaybackMediaLookup>,
    private val contexts: Provider<PlayContextResolver>,
    private val capabilities: YouTubeCapabilitiesSource,
) : UnavailablePlaybackController() {
    override suspend fun playEpisode(episodeId: Long): PlayResult {
        if (opener.openState.value !is DatabaseOpenState.Opened) return PlayResult.ServiceUnavailable

        val row = media().get(episodeId)
        val reason = capabilities.capabilities.value.externalReason
        if (row != null && row.sourceType != SourceType.RSS && reason != null) {
            return PlayResult.NotPlayable(episodeId, UnplayableReason.YouTubeExternal(reason))
        }

        return PlayResult.ServiceUnavailable
    }

    override suspend fun playEpisodeAt(
        episodeId: Long,
        positionMs: Long,
    ): PlayResult = playEpisode(episodeId)

    override suspend fun playFeed(
        source: FeedSource,
        filters: FeedFilters,
        order: FeedOrder,
        startEpisodeId: Long?,
    ): PlayResult {
        if (opener.openState.value !is DatabaseOpenState.Opened) return PlayResult.ServiceUnavailable
        if (startEpisodeId != null) return playEpisode(startEpisodeId)

        val resolver = contexts()
        val spec = resolver.spec(source, filters, order)
        if (resolver.startItem(spec) == null &&
            resolver.startItem(spec, ContextEligibility.INCLUDE_EXTERNAL_YOUTUBE) != null
        ) {
            return PlayResult.NothingToPlay(externalOnly = true)
        }

        return PlayResult.ServiceUnavailable
    }
}

/** Public for Metro aggregation. Each shell contributes exactly one binding per playback port. */
@ContributesTo(AppScope::class)
@BindingContainer
object DesktopPlaybackBindings {
    @Provides internal fun controller(stub: DesktopUnavailablePlayback): PlaybackController = stub

    @Provides internal fun state(stub: DesktopUnavailablePlayback): PlaybackStateSource = stub

    @Provides internal fun maintenance(stub: DesktopUnavailablePlayback): PlaybackMaintenance = stub
}
