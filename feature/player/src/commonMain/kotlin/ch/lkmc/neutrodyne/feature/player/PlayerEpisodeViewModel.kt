// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.EpisodeRepository
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Marking played is an episode write; the playback projector alone advances the current item. */
@ViewModelKey(PlayerEpisodeViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
public class PlayerEpisodeViewModel(
    private val episodes: EpisodeRepository,
) : ViewModel() {
    private val failed = MutableStateFlow(false)
    public val writeFailed = failed.asStateFlow()

    public fun markPlayed(episodeId: Long) {
        viewModelScope.launch {
            failed.value = suspendRunCatching { episodes.setPlayed(listOf(episodeId), true) }.isFailure
        }
    }

    public fun acknowledgeFailure() {
        failed.value = false
    }
}
