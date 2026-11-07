// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.youtube

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.data.fetch.FeedFetcher
import ch.lkmc.neutrodyne.core.data.fetch.FeedRequest
import ch.lkmc.neutrodyne.core.data.fetch.FeedTempFiles
import ch.lkmc.neutrodyne.core.data.fetch.FetchOutcome
import ch.lkmc.neutrodyne.core.data.refresh.RefreshOrigin
import ch.lkmc.neutrodyne.core.data.refresh.RefreshScheduler
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.youtube.api.FeedOutcome
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedOutageMonitor
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import ch.lkmc.neutrodyne.youtube.api.YouTubeHealth
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope

@BindingContainer
@ContributesTo(AppScope::class)
object YouTubeHealthBindings {
    @Provides
    @SingleIn(AppScope::class)
    internal fun health(
        settings: SettingsRepository,
        clock: Clock,
        @ApplicationScope scope: CoroutineScope,
        db: NeutrodyneDatabase,
        scheduler: Provider<RefreshScheduler>,
    ): YouTubeHealth =
        DefaultYouTubeHealth(settings, clock, scope) {
            val ids = db.podcastDao().youtubeChannelIds()
            if (ids.isNotEmpty()) {
                scheduler().enqueueNow(
                    RefreshScope.Podcasts(ids),
                    force = true,
                    pagesOnly = false,
                    origin = RefreshOrigin.MANUAL,
                )
            }
        }

    @Provides
    @SingleIn(AppScope::class)
    internal fun monitor(
        health: YouTubeHealth,
        clock: Clock,
        db: NeutrodyneDatabase,
        controls: YouTubeControlCheck,
    ): YouTubeFeedOutageMonitor =
        YouTubeOutageMonitor(health, clock, {
            db.podcastDao().youtubeChannelIds().size
        }, controls::check)
}

/**
 * Called under the adapter's existing host permit for probes, or after fan-out for finalization.
 * Never reacquires that permit. The monitor bounds the entire sequential check to 20 seconds.
 */
@Inject
internal class YouTubeControlCheck(
    private val fetcher: FeedFetcher,
    private val tempFiles: FeedTempFiles,
    private val network: NetworkMonitor,
    private val health: YouTubeHealth,
    private val clock: Clock,
) {
    suspend fun check(): FeedOutcome {
        for (id in YouTubeFeedUrls.CONTROL_CHANNEL_IDS) {
            if (!network.status.value.isValidated) return FeedOutcome.INCONCLUSIVE
            when (
                val result =
                    fetcher.fetch(
                        FeedRequest(YouTubeFeedUrls.canonical(id), null, null, false, maxBytes = CONTROL_MAX_BYTES),
                    )
            ) {
                is FetchOutcome.Body -> {
                    tempFiles.delete(result.file)
                    return FeedOutcome.SUCCESS
                }

                is FetchOutcome.Http -> {
                    if (result.code == 403 || result.code == 429) {
                        health.reportFeedRateLimited(clock.now(), result.retryAfterMs)
                        return FeedOutcome.INCONCLUSIVE
                    }
                    if (result.code != 404 && result.code != 410 &&
                        result.code !in 500..599
                    ) {
                        return FeedOutcome.INCONCLUSIVE
                    }
                }

                is FetchOutcome.Network -> {
                    if (result.error == NetError.Offline || result.error == NetError.Cancelled ||
                        !network.status.value.isValidated
                    ) {
                        return FeedOutcome.INCONCLUSIVE
                    }
                }

                else -> {
                    return FeedOutcome.INCONCLUSIVE
                }
            }
        }
        return FeedOutcome.FAILURE
    }

    private companion object {
        const val CONTROL_MAX_BYTES = 2L * 1024 * 1024
    }
}
