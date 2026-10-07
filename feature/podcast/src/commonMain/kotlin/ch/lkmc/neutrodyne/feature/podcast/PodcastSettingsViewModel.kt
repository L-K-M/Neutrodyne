// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.FeedRepository
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedInfo
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.PodcastDetail
import ch.lkmc.neutrodyne.core.model.ShowType
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The podcast settings screen's state (08 Podcast settings): [detail] drives the General rows and
 * [feedInfo] the Feed section. [gone] means the podcast was removed and the route pops. The "Show
 * in All" row is deferred — `includeInAll` is not in the M1a read models (deviation in 08).
 */
@Immutable
public data class PodcastSettingsUiState(
    val detail: PodcastDetail? = null,
    val feedInfo: FeedInfo? = null,
    val loaded: Boolean = false,
) {
    public val gone: Boolean
        get() = loaded && detail == null

    /** The effective order — 05's default reads serial shows oldest-first. */
    public val effectiveOrder: FeedOrder
        get() =
            detail?.episodeOrder
                ?: if (detail?.showType == ShowType.SERIAL) {
                    FeedOrder.OLDEST_FIRST
                } else {
                    FeedOrder.NEWEST_FIRST
                }
}

/**
 * The `PodcastSettingsKey` ViewModel (08 Podcast settings, general and feed rows of M1): writes go
 * to `PodcastRepository`/`FeedRepository`; `editFeedUrl`/`setCredentials` return 03's `Outcome` so
 * the screen can surface `AddPodcastError` inline. Playback/downloads/notifications/refresh rows
 * arrive with their milestones.
 */
public class PodcastSettingsViewModel
    @AssistedInject
    constructor(
        @Assisted private val podcastId: Long,
        private val podcasts: PodcastRepository,
        private val feedRepository: FeedRepository,
    ) : ViewModel() {
        public val uiState: StateFlow<PodcastSettingsUiState> =
            combine(
                podcasts.observePodcast(podcastId),
                podcasts.observeFeedInfo(podcastId),
            ) { detail, feedInfo ->
                PodcastSettingsUiState(detail = detail, feedInfo = feedInfo, loaded = true)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PodcastSettingsUiState())

        /** "Custom title" — empty input resets to the feed title (null). */
        public fun setCustomTitle(title: String?) {
            viewModelScope.launch { podcasts.setCustomTitle(podcastId, title) }
        }

        /** "Episode order" — persisted in `podcast.episodeOrder` (05). */
        public fun setOrder(order: FeedOrder) {
            viewModelScope.launch {
                feedRepository.setFeedOrder(FeedSource.Podcast(podcastId), order)
            }
        }

        /** "Edit feed address" (03 Edit URL; RSS rows only). */
        public suspend fun editFeedUrl(input: String): Outcome<Unit, AddPodcastError> =
            podcasts.editFeedUrl(podcastId, input)

        /** "Username and password" (03 Basic auth). */
        public suspend fun setCredentials(credentials: BasicCredentials): Outcome<Unit, AddPodcastError> =
            podcasts.setCredentials(podcastId, credentials)

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey(Factory::class)
        @ContributesIntoMap(AppScope::class)
        public fun interface Factory : ManualViewModelAssistedFactory {
            public fun create(podcastId: Long): PodcastSettingsViewModel
        }
    }
