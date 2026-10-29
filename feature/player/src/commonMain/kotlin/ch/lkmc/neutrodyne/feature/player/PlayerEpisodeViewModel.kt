// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.EpisodeRepository
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.EpisodeDetail
import ch.lkmc.neutrodyne.core.model.ShowNotes
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.ui.ShowNotesImageMode
import ch.lkmc.neutrodyne.core.ui.YouTubeLinks
import ch.lkmc.neutrodyne.core.ui.showNotesImageMode
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class PlayerNotesState(
    val episodeId: Long? = null,
    val notes: ShowNotes? = null,
    val loaded: Boolean = false,
    val loadFailed: Boolean = false,
    val imageMode: ShowNotesImageMode = ShowNotesImageMode.TAP_TO_LOAD,
)

/** Episode writes stay here; only the projector advances a marked current item. */
@ViewModelKey(PlayerEpisodeViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerEpisodeViewModel(
    private val episodes: EpisodeRepository,
    private val podcasts: PodcastRepository,
    settings: SettingsRepository,
    network: NetworkMonitor,
) : ViewModel() {
    private val failed = MutableStateFlow(false)
    val writeFailed = failed.asStateFlow()
    private val readFailed = MutableStateFlow(false)
    val readFailure = readFailed.asStateFlow()
    private val password = MutableStateFlow<Long?>(null)
    val passwordEpisode = password.asStateFlow()
    private val current = MutableStateFlow<Long?>(null)
    private val notesRequest = MutableStateFlow<NotesRequest?>(null)

    val detail =
        current
            .flatMapLatest { id ->
                if (id == null) {
                    flowOf<EpisodeDetail?>(null)
                } else {
                    episodes.observeEpisode(id).catch {
                        readFailed.value = true
                        emit(null)
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    val notes =
        combine(
            notesRequest.flatMapLatest { request ->
                if (request == null) {
                    flowOf(PlayerNotesState())
                } else {
                    episodes
                        .observeShowNotes(request.episodeId)
                        .map { PlayerNotesState(episodeId = request.episodeId, notes = it, loaded = true) }
                        .onStart { emit(PlayerNotesState(episodeId = request.episodeId)) }
                        .catch { emit(PlayerNotesState(episodeId = request.episodeId, loadFailed = true)) }
                }
            },
            settings.observe(FeedsSettingKeys.SHOW_NOTES_IMAGES),
            network.status,
        ) { state, images, net ->
            state.copy(imageMode = showNotesImageMode(images, net.isMetered))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), PlayerNotesState())

    fun displayEpisode(id: Long?) {
        current.value = id
    }

    fun showNotes(id: Long?) {
        if (notesRequest.value?.episodeId == id) return
        notesRequest.value = id?.let { NotesRequest(it) }
    }

    fun retryNotes() {
        notesRequest.value = notesRequest.value?.let { it.copy(attempt = it.attempt + 1) }
    }

    fun markPlayed(episodeId: Long) {
        write { episodes.setPlayed(listOf(episodeId), true) }
    }

    fun requestPassword(episodeId: Long) {
        password.value = episodeId
    }

    fun dismissPassword() {
        password.value = null
    }

    fun submitPassword(credentials: BasicCredentials) {
        // Saving does not replay audio or close a newer prompt.
        val id = password.value ?: return
        password.value = null
        write {
            val episode = episodes.observeEpisode(id).first() ?: error("Episode no longer exists")
            if (podcasts.setCredentials(episode.podcastId, credentials) is Outcome.Failure) {
                error("Could not save feed credentials")
            }
        }
    }

    suspend fun watchLink(id: Long): String? =
        episodes
            .observeEpisode(id)
            .first()
            ?.externalMediaId
            ?.let(YouTubeLinks::watch)

    fun acknowledgeFailure() {
        failed.value = false
    }

    fun acknowledgeReadFailure() {
        readFailed.value = false
    }

    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch { suspendRunCatching(block).onFailure { failed.value = true } }
    }

    private data class NotesRequest(
        val episodeId: Long,
        val attempt: Int = 0,
    )
}
