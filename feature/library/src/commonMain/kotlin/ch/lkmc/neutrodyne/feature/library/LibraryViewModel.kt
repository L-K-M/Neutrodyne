// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.TitleCollator
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.EpisodeRepository
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.domain.RefreshController
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.UnsubscribeUseCase
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.LibraryTile
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.LibrarySort
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessage
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Library grid's ViewModel (08 Library): tiles from `observeLibraryTiles(null)` (M1a has no
 * group chips — M2), sorted by the `appearance.library_sort` setting (02 returns rows unsorted;
 * TITLE goes through [TitleCollator]), titles per `appearance.library_titles`, offline per
 * `NetworkMonitor`.
 */
@ViewModelKey(LibraryViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
public class LibraryViewModel(
    private val podcasts: PodcastRepository,
    private val episodes: EpisodeRepository,
    private val refreshController: RefreshController,
    private val unsubscribe: UnsubscribeUseCase,
    private val settings: SettingsRepository,
    private val collator: TitleCollator,
    network: NetworkMonitor,
) : ViewModel() {
    private val userMessages = UserMessages()

    public val uiState: StateFlow<LibraryUiState> =
        combine(
            podcasts.observeLibraryTiles(null),
            settings.observe(AppearanceSettingKeys.LIBRARY_TITLES),
            settings.observe(AppearanceSettingKeys.LIBRARY_SORT),
            network.status.map { it.isConnected }.distinctUntilChanged(),
            userMessages.flow,
        ) { tiles, showTitles, sort, online, messages ->
            LibraryUiState(
                tiles = sortTiles(tiles, sort).toPersistentList(),
                loaded = true,
                showTitles = showTitles,
                sort = sort,
                offline = !online,
                messages = messages,
            )
        }.stateIn(viewModelScope, SHARING, LibraryUiState())

    public fun setSort(sort: LibrarySort) {
        writeSetting { settings.set(AppearanceSettingKeys.LIBRARY_SORT, sort) }
    }

    public fun setShowTitles(show: Boolean) {
        writeSetting { settings.set(AppearanceSettingKeys.LIBRARY_TITLES, show) }
    }

    /** The tile overflow's "Refresh" (03 Per-feed states: forced, user-initiated). */
    public fun refreshPodcast(podcastId: Long) {
        refreshController.refreshFeed(FeedSource.Podcast(podcastId))
    }

    /** The tile overflow's "Mark all as played". */
    public fun markAllPlayed(podcastId: Long) {
        write { episodes.markFeedPlayed(FeedSource.Podcast(podcastId), null) }
    }

    /** The downloaded-episode count the unsubscribe confirmation names (08's dialog wording). */
    public suspend fun downloadedCount(podcastId: Long): Int = podcasts.downloadedEpisodeIds(listOf(podcastId)).size

    public fun unsubscribe(podcastId: Long) {
        write { unsubscribe(listOf(podcastId)) }
    }

    /** The screen acks a shown snackbar so its [UserMessage] leaves the state. */
    public fun onMessageShown(id: Long) {
        userMessages.shown(id)
    }

    /** A failed write is logged and surfaces as a snackbar — it never escapes the scope. */
    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            suspendRunCatching { block() }.onFailure(::reportWriteFailed)
        }
    }

    /** A settings write: the repository reports failures as `Outcome.Failure`, not a throw. */
    private fun writeSetting(block: suspend () -> Outcome<Unit, *>) {
        viewModelScope.launch {
            val failed = suspendRunCatching { block() }.getOrNull() !is Outcome.Success
            if (failed) reportWriteFailed(null)
        }
    }

    private fun reportWriteFailed(t: Throwable?) {
        Log.w(TAG, t) { "write failed" }
        userMessages.post(UiText.Res(Res.string.write_failed))
    }

    private fun sortTiles(
        tiles: List<LibraryTile>,
        sort: LibrarySort,
    ): List<LibraryTile> =
        when (sort) {
            LibrarySort.TITLE -> {
                tiles.sortedWith { a, b -> collator.compare(a.displayTitle, b.displayTitle) }
            }

            LibrarySort.RECENTLY_UPDATED -> {
                tiles.sortedByDescending { it.latestEpisodeAt ?: Long.MIN_VALUE }
            }

            LibrarySort.MOST_UNPLAYED -> {
                tiles.sortedWith(
                    compareByDescending<LibraryTile> { it.unplayedCount }.then {
                        a,
                        b,
                        ->
                        collator.compare(a.displayTitle, b.displayTitle)
                    },
                )
            }

            LibrarySort.RECENTLY_ADDED -> {
                tiles.sortedByDescending { it.subscribedAt }
            }
        }

    private companion object {
        const val TAG = "LibraryViewModel"
        val SHARING = SharingStarted.WhileSubscribed(5_000)
    }
}
