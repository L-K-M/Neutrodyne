// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.TitleCollator
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.FeedRepository
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.FeedCounts
import ch.lkmc.neutrodyne.core.model.FeedSourceRef
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupCounts
import ch.lkmc.neutrodyne.core.model.GroupMosaicMember
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class AllGroupsTile(
    val group: Group,
    val members: ImmutableList<GroupMosaicMember>,
    val counts: FeedCounts,
)

internal data class AllGroupsUiState(
    val loaded: Boolean = false,
    val recent: ImmutableList<AllGroupsTile> = persistentListOf(),
    val groups: ImmutableList<AllGroupsTile> = persistentListOf(),
    val selecting: Boolean = false,
    val selected: Boolean = false,
    val error: UiText? = null,
)

/** The sheet writes the same device selection observed by the Feeds pager (08). */
@ViewModelKey(AllGroupsViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
internal class AllGroupsViewModel(
    groups: GroupRepository,
    feeds: FeedRepository,
    private val settings: SettingsRepository,
    collator: TitleCollator,
    clock: Clock,
) : ViewModel() {
    private val selectionError = MutableStateFlow<UiText?>(null)
    private val selecting = MutableStateFlow(false)
    private val selected = MutableStateFlow(false)
    private val tiles =
        combine(
            groups.observeGroups(),
            groups.observeMosaics(),
            feeds.observeGroupCounts(clock.now() - GroupCounts.WINDOW_DAYS * GroupCounts.DAY_MS),
        ) { rows, mosaics, counts ->
            rows
                .sortedWith { a, b ->
                    collator.compare(a.name, b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id)
                }.map { group ->
                    AllGroupsTile(
                        group = group,
                        members = mosaics[group.id].orEmpty().toImmutableList(),
                        counts = counts[group.id] ?: FeedCounts(),
                    )
                }.toImmutableList()
        }

    val uiState =
        combine(tiles, selecting, selected, selectionError) { all, writing, done, error ->
            AllGroupsUiState(
                loaded = true,
                recent =
                    all
                        .filter { it.group.lastViewedAt != null }
                        .sortedByDescending { it.group.lastViewedAt }
                        .take(RECENT_LIMIT)
                        .toImmutableList(),
                groups = all,
                selecting = writing,
                selected = done,
                error = error,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AllGroupsUiState())

    fun selectGroup(groupId: Long) {
        if (selecting.value || selected.value) return
        val group =
            uiState.value.groups
                .find { it.group.id == groupId }
                ?.group ?: return
        selectionError.value = null
        selecting.value = true
        viewModelScope.launch {
            suspendRunCatching {
                val ref = FeedSourceRef.Group(group.uuid)
                when (val result = settings.set(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE, ref.encode())) {
                    is Outcome.Success -> selected.value = true
                    is Outcome.Failure -> error("Selection write failed: ${result.error}")
                }
            }.onFailure { error ->
                Log.e(TAG, error) { "Group selection failed" }
                selectionError.value = UiText.Res(Res.string.write_failed)
            }
            selecting.value = false
        }
    }

    /** Consume before popping so recomposition cannot close the destination underneath. */
    fun consumeSelection(): Boolean {
        if (!selected.value) return false
        selected.value = false
        return true
    }

    private companion object {
        const val RECENT_LIMIT = 4
        const val TAG = "AllGroupsViewModel"
    }
}
