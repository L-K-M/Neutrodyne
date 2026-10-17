// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.FeedRepository
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.domain.ScopeSettingsRepository
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedInfo
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.PodcastDetail
import ch.lkmc.neutrodyne.core.model.ScopedSettingsView
import ch.lkmc.neutrodyne.core.model.SettingField
import ch.lkmc.neutrodyne.core.model.SettingOverrides
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessage
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.core.ui.platform.NotificationPermissionRequester
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The podcast settings screen's state (08 Podcast settings): [detail] drives the General rows —
 * including the "Show in All" switch on [PodcastDetail.includeInAll] — and [feedInfo] the Feed
 * section. [gone] means the podcast was removed and the route pops.
 */
@Immutable
public data class PodcastSettingsUiState(
    val detail: PodcastDetail? = null,
    val feedInfo: FeedInfo? = null,
    val loaded: Boolean = false,
    val scoped: ScopedSettingsView? = null,
    val invalidFields: Set<SettingField> = emptySet(),
    val notificationsBlocked: Boolean = false,
    val messages: ImmutableList<UserMessage> = persistentListOf(),
) {
    public val gone: Boolean
        get() = loaded && detail == null

    /** The effective order — 05's default reads serial shows oldest-first. */
    public val effectiveOrder: FeedOrder
        get() = detail.effectiveOrder()
}

/**
 * The `PodcastSettingsKey` ViewModel (08 Podcast settings, general and feed rows of M1): writes go
 * to `PodcastRepository`/`FeedRepository`; `editFeedUrl`/`setCredentials` return 03's `Outcome` so
 * the screen can surface `AddPodcastError` inline. M2 refresh and notification overrides go
 * through `ScopeSettingsRepository`.
 */
public class PodcastSettingsViewModel
    @AssistedInject
    constructor(
        @Assisted private val podcastId: Long,
        private val podcasts: PodcastRepository,
        private val feedRepository: FeedRepository,
        private val scopeSettings: ScopeSettingsRepository,
    ) : ViewModel() {
        private val userMessages = UserMessages()
        private val invalidFields = MutableStateFlow<Set<SettingField>>(emptySet())
        private val notificationsBlocked = MutableStateFlow(false)

        public val uiState: StateFlow<PodcastSettingsUiState> =
            combine(
                podcasts.observePodcast(podcastId),
                podcasts.observeFeedInfo(podcastId),
                scopeSettings.observePodcast(podcastId),
                userMessages.flow,
            ) { detail, feedInfo, scoped, messages ->
                PodcastSettingsUiState(
                    detail = detail,
                    feedInfo = feedInfo,
                    scoped = scoped,
                    loaded = true,
                    messages = messages,
                )
            }.combine(invalidFields) { state, fields -> state.copy(invalidFields = fields) }
                .combine(notificationsBlocked) { state, blocked -> state.copy(notificationsBlocked = blocked) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PodcastSettingsUiState())

        public fun setRefreshIntervalMinutes(minutes: Int?) {
            writeScope(SettingField.REFRESH_INTERVAL_MINUTES, { it.copy(refreshIntervalMinutes = minutes) })
        }

        /** Permission is a UI action after a successful explicit opt-in; denial preserves the wish. */
        public fun setNotifyNewEpisodes(
            enabled: Boolean?,
            requester: NotificationPermissionRequester?,
        ) {
            writeScope(SettingField.NOTIFY_NEW_EPISODES, { it.copy(notifyNewEpisodes = enabled) }) {
                notificationsBlocked.value = enabled == true && requester?.granted == false
                if (enabled == true && requester != null && !requester.granted) {
                    requester.request { allowed -> notificationsBlocked.value = !allowed }
                }
            }
        }

        private fun writeScope(
            field: SettingField,
            change: (SettingOverrides) -> SettingOverrides,
            onSuccess: () -> Unit = {},
        ) {
            viewModelScope.launch {
                invalidFields.update { it - field }
                val outcome =
                    suspendRunCatching { scopeSettings.updatePodcast(podcastId, change) }
                        .onFailure(::reportWriteFailed)
                        .getOrNull()
                when (outcome) {
                    is Outcome.Success -> {
                        onSuccess()
                    }

                    is Outcome.Failure -> {
                        when (outcome.error) {
                            is SettingsError.OutOfRange -> {
                                invalidFields.update { it + field }
                            }

                            SettingsError.WriteFailed -> {
                                reportWriteFailed(null)
                            }
                        }
                    }

                    null -> {
                        Unit
                    }
                }
            }
        }

        /** "Custom title" — empty input resets to the feed title (null). */
        public fun setCustomTitle(title: String?) {
            write { podcasts.setCustomTitle(podcastId, title) }
        }

        /** "Episode order" — persisted in `podcast.episodeOrder` (05). */
        public fun setOrder(order: FeedOrder) {
            write { feedRepository.setFeedOrder(FeedSource.Podcast(podcastId), order) }
        }

        /** "Show in All" — 03's `setIncludeInAll` removes the podcast's episodes from the All feed. */
        public fun setIncludeInAll(include: Boolean) {
            write { podcasts.setIncludeInAll(podcastId, include) }
        }

        /**
         * "Edit feed address" (03 Edit URL; RSS rows only). An unexpected fault is logged and
         * returns null, which the dialog shows inline; it never escapes into the UI scope.
         */
        public suspend fun editFeedUrl(input: String): Outcome<Unit, AddPodcastError>? =
            suspendRunCatching { podcasts.editFeedUrl(podcastId, input) }
                .onFailure { Log.w(TAG, it) { "edit feed URL failed" } }
                .getOrNull()

        /** "Username and password" (03 Basic auth). An unexpected fault is reported like a failed write. */
        public suspend fun setCredentials(credentials: BasicCredentials): Outcome<Unit, AddPodcastError>? =
            suspendRunCatching { podcasts.setCredentials(podcastId, credentials) }
                .onFailure(::reportWriteFailed)
                .getOrNull()

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

        private fun reportWriteFailed(t: Throwable?) {
            Log.w(TAG, t) { "write failed" }
            userMessages.post(UiText.Res(Res.string.write_failed))
        }

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey(Factory::class)
        @ContributesIntoMap(AppScope::class)
        public fun interface Factory : ManualViewModelAssistedFactory {
            public fun create(podcastId: Long): PodcastSettingsViewModel
        }

        private companion object {
            const val TAG = "PodcastSettingsViewModel"
        }
    }
