// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers.Default
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.EpisodeLiveStateSource
import ch.lkmc.neutrodyne.core.domain.EpisodeRepository
import ch.lkmc.neutrodyne.core.domain.FeedRepository
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.MarkFeedPlayedResult
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.domain.RefreshController
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.AVAILABLE_FEED_FILTER_CAPABILITIES
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedCounts
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedPrefs
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.FeedSourceRef
import ch.lkmc.neutrodyne.core.model.FeedTab
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupCounts
import ch.lkmc.neutrodyne.core.model.RowLive
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.core.model.withCapabilities
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.EpisodeSelectionController
import ch.lkmc.neutrodyne.core.ui.MarkFeedPlayedController
import ch.lkmc.neutrodyne.core.ui.MarkPlayedOutcome
import ch.lkmc.neutrodyne.core.ui.PlaybackActions
import ch.lkmc.neutrodyne.core.ui.PlayedActionOrigin
import ch.lkmc.neutrodyne.core.ui.SwipeConfig
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessage
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.core.ui.YouTubeLinks
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_copy_link
import ch.lkmc.neutrodyne.core.ui.resources.action_undo
import ch.lkmc.neutrodyne.core.ui.resources.episode_marked_played
import ch.lkmc.neutrodyne.core.ui.resources.episode_marked_unplayed
import ch.lkmc.neutrodyne.core.ui.resources.feed_group_deleted
import ch.lkmc.neutrodyne.core.ui.resources.feed_group_unknown
import ch.lkmc.neutrodyne.core.ui.resources.feeds_all
import ch.lkmc.neutrodyne.core.ui.resources.feeds_ungrouped
import ch.lkmc.neutrodyne.core.ui.resources.no_handler
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.playback.api.PlaybackStateSource
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/** Feeds pager state (08): stable UUID selection, source-owned paging caches and visit baselines. */
@ViewModelKey(FeedsViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
public class FeedsViewModel(
    private val feedRepository: FeedRepository,
    private val episodes: EpisodeRepository,
    private val refreshController: RefreshController,
    private val network: NetworkMonitor,
    private val podcasts: PodcastRepository,
    private val clock: Clock,
    liveStateSource: EpisodeLiveStateSource,
    groups: GroupRepository,
    private val settings: SettingsRepository,
    private val playbackController: PlaybackController,
    private val playbackStates: PlaybackStateSource,
    platformInfo: PlatformInfo,
    @Dispatcher(Default) private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {
    // Accessed from Main, like the ViewModel's callbacks and Compose pagingFor calls.
    private val pages = linkedMapOf<FeedSource, PageEntry>()
    private val selection = MutableStateFlow(Selection())
    private val selectionReload = MutableStateFlow(0)
    private var rejectedSelection: FeedSourceRef? = null
    private var knownGroups: List<Group> = emptyList()
    private val visits = mutableMapOf<FeedSource, Visit>()
    private val visitWrites = mutableMapOf<FeedSource, Job>()
    private val reportedSources = mutableMapOf<FeedSourceRef, FeedSource>()
    private val baselines = MutableStateFlow<Map<FeedSource, Long>>(emptyMap())
    private val userMessages = UserMessages()
    private val visiblePages = MutableStateFlow<Map<FeedSource, Set<Long>>>(emptyMap())

    public val live: StateFlow<Map<Long, RowLive>> =
        liveStateSource
            .observe(visiblePages.map { pages -> pages.values.flatten().toSet() })
            .stateIn(viewModelScope, SHARING, emptyMap())

    /** Each composed page owns its window; disposal reports an empty set to release it. */
    public fun onVisibleIds(
        page: FeedSource,
        ids: Set<Long>,
    ) {
        visiblePages.value =
            if (ids.isEmpty()) visiblePages.value - page else visiblePages.value + (page to ids)
    }

    /** Message id → URL behind a no-handler snackbar's "Copy link" action (08). */
    private val copyLinks = mutableMapOf<Long, String>()
    private val swipeUndos = mutableMapOf<Long, EpisodeAction.SetPlayed>()

    /** Evicting an entry also stops cachedIn's upstream collector, even with no UI subscriber. */
    @OptIn(ExperimentalCoroutinesApi::class)
    public fun pagingFor(source: FeedSource): Flow<PagingData<FeedItem>> {
        pages.remove(source)?.let { entry ->
            pages[source] = entry
            return entry.flow
        }

        if (pages.size == PAGE_CACHE_SIZE) evict(pages.keys.first())
        val scope =
            CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
        // Compare only the available filters/order so gated flags, visits and play-order
        // writes keep this entry's generation and scroll position.
        val flow =
            prefsFor(source)
                .map { it.filters to it.feedOrder }
                .distinctUntilChanged()
                .flatMapLatest { (filters, order) -> feedRepository.pagedFeed(source, filters, order) }
                .withDayHeaders(clock, defaultDispatcher)
                .cachedIn(scope)
        pages[source] = PageEntry(scope, flow)
        return flow
    }

    private fun evict(source: FeedSource) {
        pages.remove(source)?.scope?.cancel()
        visiblePages.value -= source
        reportedSources.entries.removeAll { it.value == source }
    }

    public val liveBySource: StateFlow<Map<FeedSource, Map<Long, RowLive>>> =
        combine(live, visiblePages) { rows, windows ->
            windows.mapValues { (_, ids) -> rows.filterKeys { it in ids } }
        }.stateIn(viewModelScope, SHARING, emptyMap())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val selectionInputs =
        combine(
            settings.observe(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE),
            selectionReload,
        ) { encoded, _ -> encoded }
            .flatMapLatest { encoded ->
                // A route may follow a create/restore transaction before the old observers have
                // delivered it. Resolve each new reference against fresh repository emissions.
                combine(feedRepository.observeTabs(), groups.observeGroups()) { tabs, allGroups ->
                    Triple(tabs, allGroups, FeedSourceRef.decode(encoded))
                }
            }

    init {
        viewModelScope.launch {
            // No initial empty tabs/groups stand in for a repository emission: a saved UUID
            // must not be rejected while either repository is still loading.
            selectionInputs.collect { (tabs, allGroups, requested) ->
                knownGroups = allGroups
                val visible = tabs.map { FeedTabUi(it, it.ref()) }
                // Undo must not revive a rejected selection while its fallback write
                // is still pending. An explicit settle may retry it; passive undo cannot.
                val target = requested.takeUnless { it == rejectedSelection } ?: FeedSourceRef.All
                val hidden =
                    (target as? FeedSourceRef.Group)?.let { ref ->
                        allGroups.find { it.uuid == ref.uuid }
                    }
                val selectedTab =
                    visible.find { it.ref == target }
                        ?: hidden?.let { group ->
                            FeedTabUi(
                                FeedTab(
                                    FeedSource.Group(group.id),
                                    group.name,
                                    group.colorArgb,
                                    group.iconKey,
                                    group.uuid,
                                ),
                                target,
                                transient = true,
                            )
                        }
                val resolved = selectedTab?.ref ?: FeedSourceRef.All
                if (requested is FeedSourceRef.Group && selectedTab == null && rejectedSelection != requested) {
                    val previous = selection.value.tabs.find { it.ref == requested }
                    userMessages.post(
                        if (previous != null) {
                            UiText.Res(Res.string.feed_group_deleted, listOf(previous.tab.title))
                        } else {
                            UiText.Res(Res.string.feed_group_unknown)
                        },
                    )
                }
                val needsFallback = requested != resolved && rejectedSelection != requested
                rejectedSelection = requested.takeIf { it != resolved }
                selection.value =
                    Selection(
                        tabs = if (selectedTab?.transient == true) visible + selectedTab else visible,
                        selected = resolved,
                        hasGroups = allGroups.isNotEmpty(),
                    )
                if (needsFallback) persistSelection(resolved)

                val sources = tabs.map { it.source }.toSet() + allGroups.map { FeedSource.Group(it.id) }
                pages.keys.filter { it is FeedSource.Group && it !in sources }.forEach(::evict)
            }
        }
    }

    private val countSince = clock.now() - GroupCounts.WINDOW_DAYS * GroupCounts.DAY_MS
    private val tabsWithCounts =
        combine(
            selection,
            feedRepository.observeGroupCounts(countSince),
            feedRepository.observeVirtualCounts(countSince),
        ) { selected, groupsById, virtual ->
            selected.copy(
                tabs =
                    selected.tabs
                        .map { tab ->
                            val counts =
                                when (val source = tab.tab.source) {
                                    FeedSource.All -> virtual.all
                                    FeedSource.Ungrouped -> virtual.ungrouped
                                    is FeedSource.Group -> groupsById[source.groupId] ?: FeedCounts()
                                    is FeedSource.Podcast -> error("Podcast is not a feed tab")
                                }
                            tab.copy(unplayed = counts.unplayed, newCount = counts.newSinceVisit)
                        },
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val selectedPrefs =
        selection.map { it.source }.distinctUntilChanged().flatMapLatest { source ->
            prefsFor(source).map { source to it }
        }

    /** Each composed page observes its own chips, including prefetched neighbours. */
    public fun prefsFor(source: FeedSource): Flow<FeedPrefs> =
        feedRepository.observePrefs(source).map { prefs ->
            prefs.copy(filters = prefs.filters.withCapabilities(AVAILABLE_FEED_FILTER_CAPABILITIES))
        }

    // Retain the All filters field for existing consumers; pages use prefsFor(source).
    private val screenState =
        combine(
            prefsFor(FeedSource.All),
            network.status.map { it.isConnected }.distinctUntilChanged(),
            refreshIndicator(),
            podcasts.observeLibraryTiles(null).map { it.isNotEmpty() },
            userMessages.flow,
        ) { allPrefs, online, refreshing, hasSubscriptions, messages ->
            FeedsUiState(
                filters = allPrefs.filters,
                offline = !online,
                refreshing = refreshing,
                hasSubscriptions = hasSubscriptions,
                messages = messages,
            )
        }

    private val swipeSettings =
        combine(
            settings.observe(AppearanceSettingKeys.FEEDS_ROW_SWIPE),
            settings.observe(AppearanceSettingKeys.SWIPE_START_ACTION),
            settings.observe(AppearanceSettingKeys.SWIPE_END_ACTION),
        ) { enabled, start, end -> if (enabled) SwipeConfig(start, end) else null }

    public val uiState: StateFlow<FeedsUiState> =
        combine(
            screenState,
            tabsWithCounts,
            selectedPrefs,
            baselines,
            swipeSettings,
        ) { screen, selected, (prefsSource, prefs), visits, swipe ->
            screen.copy(
                rowSwipe = swipe != null,
                swipe = swipe,
                tabs = selected.tabs.toImmutableList(),
                selected = selected.selected,
                hasGroups = selected.hasGroups,
                prefs = prefs.takeIf { selected.tabs.isNotEmpty() && prefsSource == selected.source },
                visitBaselines = visits.toImmutableMap(),
            )
        }.stateIn(viewModelScope, SHARING, FeedsUiState())

    public fun onPageSettled(ref: FeedSourceRef) {
        rejectedSelection = null
        persistSelection(ref)
    }

    private fun persistSelection(ref: FeedSourceRef) {
        write {
            when (val result = settings.set(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE, ref.encode())) {
                is Outcome.Success -> {
                    // DataStore deduplicates equal values. A successful explicit re-settle
                    // must still resolve a ref rejected before a failed fallback write.
                    selectionReload.value++
                }

                is Outcome.Failure -> {
                    reportWriteFailed(
                        IllegalStateException("Selection write failed: ${result.error}"),
                    )
                }
            }
        }
    }

    public fun onVisibleIds(
        page: FeedSourceRef,
        ids: Set<Long>,
    ) {
        val previous = reportedSources[page]
        val source =
            when (page) {
                FeedSourceRef.All -> {
                    FeedSource.All
                }

                FeedSourceRef.Ungrouped -> {
                    FeedSource.Ungrouped
                }

                is FeedSourceRef.Group -> {
                    selection.value.tabs
                        .find { it.ref == page }
                        ?.tab
                        ?.source ?: previous ?: return
                }
            }
        // Disposal can arrive after the transient tab disappeared. Keep its last source
        // until that empty report so the live query releases every page's window.
        if (previous != null && previous != source) onVisibleIds(previous, emptySet())
        if (ids.isEmpty()) reportedSources.remove(page) else reportedSources[page] = source
        onVisibleIds(source, ids)
    }

    /** The caller pairs lifecycle resume/pause and settled-page changes; duplicate starts are inert. */
    public fun onVisitStart(source: FeedSource) {
        if (source in visits) return
        val visit = Visit(clock.elapsedRealtime())
        visits[source] = visit
        baselines.value -= source
        val initialBaseline = knownGroups.find { FeedSource.Group(it.id) == source }?.createdAt ?: clock.now()
        val pendingWrite = visitWrites[source]
        visit.read =
            viewModelScope.launch {
                pendingWrite?.join()
                suspendRunCatching { feedRepository.observePrefs(source).first() }
                    .onSuccess { baselines.value += source to (it.lastViewedAt ?: initialBaseline) }
                    .onFailure(::reportWriteFailed)
            }
    }

    public fun onVisitEnd(source: FeedSource) {
        val visit = visits.remove(source) ?: return
        visit.read?.cancel()
        if (clock.elapsedRealtime() - visit.startedAt < MIN_VISIT_MS) return
        val leftAt = clock.now()
        val pendingWrite = visitWrites[source]
        visitWrites[source] =
            write {
                pendingWrite?.join()
                feedRepository.markVisited(source, leftAt)
            }
    }

    public val episodeSelection =
        EpisodeSelectionController(viewModelScope, episodes::setPlayed, ::reportWriteFailed)

    /**
     * Shared start/queue/consent handling (08 Issues, results and events); the route also
     * resolves playback snackbar actions through `playback.onMessageAction`.
     */
    public val playback =
        PlaybackActions(
            viewModelScope,
            playbackController,
            playbackStates,
            userMessages,
            platformInfo.kind,
            allowMeteredStreaming = ::allowMeteredStreaming,
            onFailure = ::reportWriteFailed,
        )

    /** The Enter password dialog's target podcast, set by an AuthRequired snackbar action. */
    private val credentialsPrompt = MutableStateFlow<Long?>(null)
    public val pendingCredentials: StateFlow<Long?> = credentialsPrompt.asStateFlow()

    /** The page header's Play button (08 Feeds): the source's own filters and play order. */
    public fun playFeed(source: FeedSource) {
        playback.play(feedTitle(source)) {
            val prefs = prefsFor(source).first()
            playbackController.playFeed(source, prefs.filters, prefs.playOrder)
        }
    }

    /** The page emitting the row action owns its context, including during a pager transition. */
    public fun playEpisode(
        source: FeedSource,
        episodeId: Long,
    ) {
        playback.playToggle(episodeId, feedTitle(source)) {
            val prefs = prefsFor(source).first()
            playbackController.playFeed(source, prefs.filters, prefs.playOrder, episodeId)
        }
    }

    /** Selection mode's "Play next"/"Play last" (08 Selection mode); clears the selection. */
    public fun playSelectionNext() {
        val ids = episodeSelection.ids.value.toList()
        playback.playNext(ids) { episodeSelection.deselect(ids) }
    }

    public fun playSelectionLast() {
        val ids = episodeSelection.ids.value.toList()
        playback.playLast(ids) { episodeSelection.deselect(ids) }
    }

    /** Repository-owned row actions (08 EpisodeRow); the route owns navigation and URL opens. */
    public fun onRowAction(action: EpisodeAction) {
        when (action) {
            is EpisodeAction.Select -> {
                episodeSelection.toggle(action.episodeId)
            }

            is EpisodeAction.SetPlayed -> {
                write {
                    episodes.setPlayed(listOf(action.episodeId), action.played)
                    if (action.origin == PlayedActionOrigin.SWIPE) {
                        val label =
                            if (action.played) {
                                Res.string.episode_marked_played
                            } else {
                                Res.string.episode_marked_unplayed
                            }
                        val message =
                            userMessages.post(
                                UiText.Res(label),
                                UiText.Res(Res.string.action_undo),
                            )
                        swipeUndos[message.id] = action
                    }
                }
            }

            // Row play anchors the current page's feed context at that episode (05 Start
            // rules, 08 Feeds actions); the shared toggle pauses/resumes the current item.
            is EpisodeAction.PlayToggle -> {
                playEpisode(selection.value.source, action.episodeId)
            }

            is EpisodeAction.PlayNext -> {
                playback.playNext(listOf(action.episodeId))
            }

            is EpisodeAction.PlayLast -> {
                playback.playLast(listOf(action.episodeId))
            }

            // DownloadToggle needs the download engine (M6), CheckAvailability the YouTube
            // engine (M8).
            else -> {
                Unit
            }
        }
    }

    /**
     * An `AuthRequired` snackbar's Enter action (08): the episode's podcast owns the
     * credentials, so the prompt resolves it before the dialog opens.
     */
    public fun onEnterPassword(episodeId: Long) {
        viewModelScope.launch {
            val podcastId =
                suspendRunCatching { episodes.observeEpisode(episodeId).first()?.podcastId }
                    .getOrNull() ?: return@launch
            credentialsPrompt.value = podcastId
        }
    }

    /** The Enter password dialog's commit; failures surface as a snackbar (03 Basic auth). */
    public fun onCredentials(credentials: BasicCredentials) {
        val podcastId = credentialsPrompt.value ?: return
        credentialsPrompt.value = null
        viewModelScope.launch {
            val outcome = suspendRunCatching { podcasts.setCredentials(podcastId, credentials) }.getOrNull()
            if (outcome == null || outcome is Outcome.Failure) {
                reportWriteFailed(IllegalStateException("setCredentials($podcastId) failed"))
            }
        }
    }

    public fun onCredentialsDismissed() {
        credentialsPrompt.value = null
    }

    /**
     * The counted "Mark all as played" confirmation. It lives in the ViewModel, so it survives
     * configuration changes but not process death: a destructive confirmation is not restored.
     */
    public val markPlayed: MarkFeedPlayedController =
        MarkFeedPlayedController(viewModelScope, clock, feedRepository::countUnplayed) { source, before, count ->
            when (val result = episodes.markFeedPlayed(source, before, count)) {
                is MarkFeedPlayedResult.Played -> MarkPlayedOutcome.Played
                is MarkFeedPlayedResult.SelectionChanged -> MarkPlayedOutcome.SelectionChanged(result.actualCount)
            }
        }

    /** Use the page that opened the overflow, including virtual feeds and hidden groups. */
    public fun markAllPlayed(source: FeedSource = FeedSource.All) {
        val title =
            when (source) {
                FeedSource.All -> UiText.Res(Res.string.feeds_all)
                FeedSource.Ungrouped -> UiText.Res(Res.string.feeds_ungrouped)
                is FeedSource.Group -> UiText.Raw(knownGroups.find { it.id == source.groupId }?.name ?: return)
                is FeedSource.Podcast -> return
            }
        markPlayed.open(source, title)
    }

    /** Pull-to-refresh / the desktop's Refresh button (08: All → all feeds, 20 s cooldown). */
    public fun onRefresh(source: FeedSource = FeedSource.All) {
        refreshController.refreshFeed(source)
    }

    public fun onFilters(
        source: FeedSource,
        filters: FeedFilters,
    ) {
        write { feedRepository.setFilters(source, filters) }
    }

    public fun onFiltersChange(newFilters: FeedFilters) {
        onFilters(FeedSource.All, newFilters)
    }

    /** The screen acks a shown snackbar so its [UserMessage] leaves the state. */
    public fun onMessageShown(id: Long) {
        copyLinks.remove(id)
        swipeUndos.remove(id)
        playback.onMessageShown(id)
        userMessages.shown(id)
    }

    /** A `NO_HANDLER` external open: "No app can open this link" with a "Copy link" action (08). */
    public fun onNoHandler(url: String) {
        val message =
            userMessages.post(
                UiText.Res(Res.string.no_handler),
                UiText.Res(Res.string.action_copy_link),
            )
        copyLinks[message.id] = url
    }

    public fun undoSwipe(id: Long) {
        val action = swipeUndos.remove(id) ?: return
        write { episodes.setPlayed(listOf(action.episodeId), !action.played) }
    }

    /** The route resolves a snackbar action by id; a copy-link message hands its URL back once. */
    public fun consumeCopyLink(id: Long): String? = copyLinks.remove(id)

    /** The refused YouTube start's watch URL, when the episode still has one (08). */
    public suspend fun youtubeWatchUrl(episodeId: Long): String? =
        suspendRunCatching { episodes.observeEpisode(episodeId).first()?.externalMediaId }
            .getOrNull()
            ?.let(YouTubeLinks::watch)

    /** The feed's title for "Nothing unplayed in '{context}'" (08; groups use user names). */
    private fun feedTitle(source: FeedSource): UiText? =
        when (source) {
            FeedSource.All -> {
                UiText.Res(Res.string.feeds_all)
            }

            FeedSource.Ungrouped -> {
                UiText.Res(Res.string.feeds_ungrouped)
            }

            is FeedSource.Group -> {
                knownGroups.find { it.id == source.groupId }?.name?.let(UiText::Raw)
            }

            is FeedSource.Podcast -> {
                null
            }
        }

    /** Always-consent persists `playback.stream_on_metered = ALLOW` (08's metered dialog). */
    private suspend fun allowMeteredStreaming() {
        when (val result = settings.set(PlaybackSettingKeys.STREAM_ON_METERED, StreamOnMetered.ALLOW)) {
            is Outcome.Success -> {
                Unit
            }

            is Outcome.Failure -> {
                error("playback.stream_on_metered write failed: ${result.error}")
            }
        }
    }

    /** A failed write is logged and surfaces as a snackbar — it never escapes the scope. */
    private fun write(block: suspend () -> Unit): Job =
        viewModelScope.launch {
            suspendRunCatching { block() }.onFailure(::reportWriteFailed)
        }

    private fun reportWriteFailed(t: Throwable) {
        Log.w(TAG, t) { "write failed" }
        userMessages.post(UiText.Res(Res.string.write_failed))
    }

    /** 08 Pull to refresh: the indicator tracks `running`, but never longer than 30 s. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun refreshIndicator(): Flow<Boolean> =
        refreshController
            .observeStatus()
            .map { it.running }
            .distinctUntilChanged()
            .transformLatest { running ->
                if (!running) {
                    emit(false)
                    return@transformLatest
                }
                emit(true)
                // The refresh itself continues past the cap, silently (08).
                delay(REFRESH_INDICATOR_MAX)
                emit(false)
            }

    private data class PageEntry(
        val scope: CoroutineScope,
        val flow: Flow<PagingData<FeedItem>>,
    )

    private data class Visit(
        val startedAt: Long,
        var read: Job? = null,
    )

    private data class Selection(
        val tabs: List<FeedTabUi> = emptyList(),
        val selected: FeedSourceRef = FeedSourceRef.All,
        val hasGroups: Boolean = false,
    ) {
        val source: FeedSource get() = tabs.find { it.ref == selected }?.tab?.source ?: FeedSource.All
    }

    private fun FeedTab.ref(): FeedSourceRef =
        when (source) {
            FeedSource.All -> FeedSourceRef.All
            FeedSource.Ungrouped -> FeedSourceRef.Ungrouped
            is FeedSource.Group -> FeedSourceRef.Group(requireNotNull(groupUuid))
            is FeedSource.Podcast -> error("Podcast is not a feed tab")
        }

    private companion object {
        const val PAGE_CACHE_SIZE = 3
        const val MIN_VISIT_MS = 1_000L
        const val TAG = "FeedsViewModel"
        val SHARING = SharingStarted.WhileSubscribed(5_000)
        val REFRESH_INDICATOR_MAX = 30.seconds
    }
}
