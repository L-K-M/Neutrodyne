// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.model.CategoryCount
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.settings.DiscoverSettingKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One screen-owned instance; creation snapshots selected suggestions, then counts only committed writes. */
class SuggestedGroups(
    private val podcasts: PodcastRepository,
    private val groups: GroupRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    data class State(
        val suggestions: List<CategoryCount> = emptyList(),
        val selected: Set<String> = emptySet(),
        val busy: Boolean = false,
        val result: Result? = null,
    )

    data class Result(
        val created: Int,
        val failed: Int,
        val firstGroupUuid: String? = null,
    )

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            combine(
                podcasts.observeCategoryCounts(),
                settings.observe(DiscoverSettingKeys.SUGGESTED_GROUPS_DISMISSED),
            ) { suggestions, dismissed ->
                if (dismissed) emptyList() else suggestions
            }.collect { suggestions ->
                mutableState.update {
                    it.copy(
                        suggestions = suggestions,
                        selected = it.selected.intersect(suggestions.map { suggestion -> suggestion.category }.toSet()),
                    )
                }
            }
        }
    }

    fun toggle(category: String) {
        val current = state.value
        if (current.busy || current.suggestions.none { it.category == category }) return
        mutableState.update {
            it.copy(
                selected =
                    if (category in
                        it.selected
                    ) {
                        it.selected - category
                    } else {
                        it.selected + category
                    },
            )
        }
    }

    fun dismiss() {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true) }
        scope.launch {
            try {
                val result =
                    suspendRunCatching {
                        settings.set(
                            DiscoverSettingKeys.SUGGESTED_GROUPS_DISMISSED,
                            true,
                        )
                    }.getOrNull()
                if (result !is Outcome.Success) mutableState.update { it.copy(result = Result(0, 1)) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun create() {
        val current = state.value
        if (current.busy || current.result != null) return
        val selected = current.suggestions.filter { it.category in current.selected }
        if (selected.isEmpty()) return
        mutableState.update { it.copy(busy = true) }
        scope.launch {
            var created = 0
            var failed = 0
            var firstUuid: String? = null
            val completed = mutableSetOf<String>()
            try {
                for (suggestion in selected) {
                    val result =
                        suspendRunCatching {
                            // A null colour uses the established repository palette; membership is atomic.
                            groups.create(GroupDraft(suggestion.category), suggestion.podcastIds.toSet())
                        }.getOrNull()
                    if (result is Outcome.Success) {
                        created++
                        completed += suggestion.category
                        if (firstUuid ==
                            null
                        ) {
                            firstUuid =
                                suspendRunCatching { groups.observeGroup(result.value).first()?.uuid }.getOrNull()
                        }
                    } else {
                        failed++
                    }
                }
                mutableState.update {
                    it.copy(
                        selected = it.selected - completed,
                        result = Result(created, failed, firstUuid),
                    )
                }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun consumeResult() = mutableState.update { it.copy(result = null) }
}
