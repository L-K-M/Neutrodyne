// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.episode

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.EpisodeRepository
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.EpisodeDetail
import ch.lkmc.neutrodyne.core.model.ShowNotes
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.ShowNotesImages
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.ShowNotesImageMode
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessage
import ch.lkmc.neutrodyne.core.ui.UserMessages
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The episode detail screen's state (08 Episode detail): [loaded] separates the skeleton from a
 * settled emission; [gone] (a `null` episode after the first emission) renders "no longer
 * available". `combine` only fires once every input has emitted, so a `null` [notes] already
 * means "no notes" and gets the empty text. [imageMode] folds `feeds.show_notes_images` and the
 * metered state into the renderer's three modes.
 */
@Immutable
public data class EpisodeUiState(
    val episode: EpisodeDetail? = null,
    val notes: ShowNotes? = null,
    val loaded: Boolean = false,
    val offline: Boolean = false,
    val imageMode: ShowNotesImageMode = ShowNotesImageMode.TAP_TO_LOAD,
    val messages: ImmutableList<UserMessage> = persistentListOf(),
) {
    public val gone: Boolean
        get() = loaded && episode == null
}

/**
 * The `EpisodeKey` ViewModel (08 Episode detail): the episode and its show notes come from
 * `EpisodeRepository`; played/favourite writes are repository calls. Play, queue and download
 * actions dispatch but stay inert until M4/M6 bind their controllers (deviation in 08).
 */
public class EpisodeViewModel
    @AssistedInject
    constructor(
        @Assisted private val episodeId: Long,
        private val episodes: EpisodeRepository,
        settings: SettingsRepository,
        network: NetworkMonitor,
    ) : ViewModel() {
        private val userMessages = UserMessages()

        public val uiState: StateFlow<EpisodeUiState> =
            combine(
                episodes.observeEpisode(episodeId),
                episodes.observeShowNotes(episodeId),
                network.status,
                settings.observe(FeedsSettingKeys.SHOW_NOTES_IMAGES),
                userMessages.flow,
            ) { episode, notes, net, images, messages ->
                EpisodeUiState(
                    episode = episode,
                    notes = notes,
                    loaded = true,
                    offline = !net.isConnected,
                    imageMode = imageMode(images, net.isMetered),
                    messages = messages,
                )
            }.stateIn(viewModelScope, SHARING, EpisodeUiState())

        /** Repository-owned actions; the route owns navigation, URLs, share and clipboard. */
        public fun onAction(action: EpisodeAction) {
            when (action) {
                is EpisodeAction.SetPlayed -> {
                    write { episodes.setPlayed(listOf(action.episodeId), action.played) }
                }

                // PlayToggle/PlayNext/PlayLast need the player (M4); DownloadToggle needs M6;
                // CheckAvailability needs the YouTube engine (M8).
                else -> {
                    Unit
                }
            }
        }

        /** The screen acks a shown snackbar so its [UserMessage] leaves the state. */
        public fun onMessageShown(id: Long) {
            userMessages.shown(id)
        }

        public fun setFavorite(favorite: Boolean) {
            write { episodes.setFavorite(episodeId, favorite) }
        }

        public fun setPlayed(played: Boolean) {
            write { episodes.setPlayed(listOf(episodeId), played) }
        }

        /** A failed write is logged and surfaces as a snackbar — it never escapes the scope. */
        private fun write(block: suspend () -> Unit) {
            viewModelScope.launch {
                suspendRunCatching { block() }.onFailure(::reportWriteFailed)
            }
        }

        private fun reportWriteFailed(t: Throwable) {
            Log.w(TAG, t) { "write failed" }
            userMessages.post(UiText.Res(Res.string.write_failed))
        }

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey(Factory::class)
        @ContributesIntoMap(AppScope::class)
        public fun interface Factory : ManualViewModelAssistedFactory {
            public fun create(episodeId: Long): EpisodeViewModel
        }

        private companion object {
            const val TAG = "EpisodeViewModel"
            val SHARING = SharingStarted.WhileSubscribed(5_000)
        }
    }

/** `feeds.show_notes_images` + metered → the renderer's three modes (03 Images and links, PO-21). */
private fun imageMode(
    setting: ShowNotesImages,
    metered: Boolean,
): ShowNotesImageMode =
    when (setting) {
        ShowNotesImages.ALWAYS -> {
            ShowNotesImageMode.SHOWN
        }

        ShowNotesImages.TAP_TO_LOAD -> {
            ShowNotesImageMode.TAP_TO_LOAD
        }

        ShowNotesImages.WIFI_ONLY -> {
            if (metered) ShowNotesImageMode.BLOCKED else ShowNotesImageMode.SHOWN
        }
    }
