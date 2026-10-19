// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.ManualMeteredPolicy
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.core.ui.DownloadRequestHandler
import ch.lkmc.neutrodyne.core.ui.PlaybackActions
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessage
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.downloads_file_unavailable
import ch.lkmc.neutrodyne.core.ui.resources.nav_downloads
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import ch.lkmc.neutrodyne.download.api.DownloadController
import ch.lkmc.neutrodyne.download.api.DownloadNotice
import ch.lkmc.neutrodyne.download.api.DownloadProgressSource
import ch.lkmc.neutrodyne.download.api.DownloadsOverview
import ch.lkmc.neutrodyne.download.api.OrphanDeleteResult
import ch.lkmc.neutrodyne.download.api.ShareableFile
import ch.lkmc.neutrodyne.download.api.StorageUsage
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.playback.api.PlaybackStateSource
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

public data class DownloadsUiState(
    val overview: DownloadsOverview? = null,
    val storage: StorageUsage? = null,
    val selected: Set<Long> = emptySet(),
    val messages: ImmutableList<UserMessage> = persistentListOf(),
    val failed: Boolean = false,
    val offline: Boolean = false,
)

/** At most two notices, in 07's order. Deferred producers cannot expose M6b controls. */
public fun manualDownloadNotices(notices: Set<DownloadNotice>): List<DownloadNotice> =
    DownloadNotice.entries
        .filter {
            it in notices &&
                it !in setOf(DownloadNotice.CAP_REACHED, DownloadNotice.MOVE_IN_PROGRESS, DownloadNotice.YOUTUBE_PAUSED)
        }.take(2)

@ViewModelKey(DownloadsViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
public class DownloadsViewModel(
    private val controller: DownloadController,
    progress: DownloadProgressSource,
    private val player: PlaybackController,
    playbackStates: PlaybackStateSource,
    private val settings: SettingsRepository,
    platform: PlatformInfo,
    network: NetworkMonitor,
) : ViewModel() {
    private val messages = UserMessages()
    private val overview = MutableStateFlow<DownloadsOverview?>(null)
    private val storage = MutableStateFlow<StorageUsage?>(null)
    private val failed = MutableStateFlow(false)
    private val visible = MutableStateFlow<Set<Long>>(emptySet())
    private val selected = MutableStateFlow<Set<Long>>(emptySet())
    private val orphanState = MutableStateFlow<Long?>(null)
    public val orphanConfirmation: kotlinx.coroutines.flow.StateFlow<Long?> = orphanState
    public val downloads =
        DownloadRequestHandler(
            viewModelScope,
            controller,
            messages,
            allowAlways = {
                check(settings.set(DownloadSettingKeys.MANUAL_METERED, ManualMeteredPolicy.ALWAYS) is Outcome.Success)
            },
            markNotificationPrompted = {
                check(
                    settings.set(DownloadSettingKeys.NOTIFICATION_PROMPTED, true) is Outcome.Success,
                )
            },
            onFailure = ::reportFailure,
        )
    public val playback =
        PlaybackActions(
            viewModelScope,
            player,
            playbackStates,
            messages,
            platform.kind,
            allowMeteredStreaming = {
                check(settings.set(PlaybackSettingKeys.STREAM_ON_METERED, StreamOnMetered.ALLOW) is Outcome.Success)
            },
            onFailure = ::reportFailure,
        )

    public val uiState =
        combine(overview, storage, selected, messages.flow, failed) { overview, storage, selected, messages, failed ->
            DownloadsUiState(overview, storage, selected, messages, failed)
        }.combine(network.status) { state, status -> state.copy(offline = !status.isConnected) }
            .stateIn(viewModelScope, SHARING, DownloadsUiState())

    @OptIn(ExperimentalCoroutinesApi::class)
    public val live = visible.flatMapLatest(progress::observe).stateIn(viewModelScope, SHARING, emptyMap())

    private var overviewJob: kotlinx.coroutines.Job? = null
    private var storageJob: kotlinx.coroutines.Job? = null

    init {
        reload()
    }

    public fun reload() {
        overviewJob?.cancel()
        storageJob?.cancel()
        failed.value = false
        overviewJob =
            run {
                controller.observeAll().collect { value ->
                    overview.value = value
                    // Removed files must not remain selected across background reconciliation.
                    selected.value = selected.value.intersect(value.completed.map { it.status.episodeId }.toSet())
                }
            }
        storageJob = run { controller.observeStorage().collect { storage.value = it } }
    }

    public fun onVisibleIds(ids: Set<Long>) {
        val active =
            overview.value
                ?.inProgress
                .orEmpty()
                .map { it.status.episodeId }
                .toSet()
        visible.value = ids.intersect(active)
    }

    public fun toggleSelection(id: Long) {
        if (overview.value?.completed?.none { it.status.episodeId == id } != false) return
        selected.value = if (id in selected.value) selected.value - id else selected.value + id
    }

    public fun selectAllCompleted() {
        selected.value =
            overview.value
                ?.completed
                .orEmpty()
                .map { it.status.episodeId }
                .toSet()
    }

    public fun selectPlayed() {
        selected.value =
            overview.value
                ?.completed
                .orEmpty()
                .filter { it.played }
                .map { it.status.episodeId }
                .toSet()
    }

    public fun clearSelection() {
        selected.value = emptySet()
    }

    public fun deleteSelection() {
        val rows =
            overview.value
                ?.completed
                .orEmpty()
                .filter { it.status.episodeId in selected.value }
        downloads.confirmDelete(
            rows.map { it.status.episodeId },
            rows.sumOf {
                it.status.totalBytes
                    ?: it.status.downloadedBytes
            },
        )
    }

    public fun delete(id: Long) {
        val row = overview.value?.completed?.find { it.status.episodeId == id } ?: return
        downloads.confirmDelete(listOf(id), row.status.totalBytes ?: row.status.downloadedBytes)
    }

    public fun pauseAll() {
        run { controller.pauseAll() }
    }

    public fun resumeAll() {
        run { controller.resumeAll() }
    }

    public fun retryAll() {
        run {
            controller.retry(
                overview.value
                    ?.failed
                    .orEmpty()
                    .map { it.status.episodeId },
            )
        }
    }

    public fun retry(id: Long) {
        run { controller.retry(listOf(id)) }
    }

    public fun play(id: Long? = null) {
        playback.play(UiText.Res(Res.string.nav_downloads)) { player.playDownloads(id) }
    }

    public suspend fun shareFile(id: Long): ShareableFile? =
        suspendRunCatching { controller.shareableFile(id) }.getOrNull().also {
            if (it == null) messages.post(UiText.Res(Res.string.downloads_file_unavailable))
        }

    public fun askDeleteOrphans() {
        orphanState.value = storage.value?.orphanBytes
    }

    public fun dismissOrphans() {
        orphanState.value = null
    }

    public fun deleteOrphans() {
        if (orphanState.value == null) return
        orphanState.value = null
        run { check(controller.deleteOrphanFiles() is OrphanDeleteResult.Deleted) }
    }

    public fun onMessageShown(id: Long) {
        messages.shown(id)
        playback.onMessageShown(id)
    }

    private fun run(block: suspend () -> Unit) =
        viewModelScope.launch {
            suspendRunCatching { block() }.onFailure(::reportFailure)
        }

    private fun reportFailure(error: Throwable) {
        ch.lkmc.neutrodyne.core.common.Log
            .w("DownloadsViewModel", error) { "download action failed" }
        failed.value = overview.value == null
        messages.post(UiText.Res(Res.string.write_failed))
    }

    private companion object {
        val SHARING = SharingStarted.WhileSubscribed(5_000)
    }
}
