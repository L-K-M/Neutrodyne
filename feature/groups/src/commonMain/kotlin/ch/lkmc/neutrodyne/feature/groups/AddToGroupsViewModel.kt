// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.membership_changed
import ch.lkmc.neutrodyne.core.ui.resources.membership_name_empty
import ch.lkmc.neutrodyne.core.ui.resources.membership_name_taken
import ch.lkmc.neutrodyne.core.ui.resources.membership_name_too_long
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class MembershipChip(
    val group: Group,
    val selection: ToggleableState,
)

internal data class AddToGroupsUiState(
    val chips: List<MembershipChip> = emptyList(),
    val podcastCount: Int = 0,
    val podcastTitle: String? = null,
    val ready: Boolean = false,
    val busy: Boolean = false,
    val name: String? = null,
    val nameError: UiText? = null,
    val doneCount: Int? = null,
)

internal class AddToGroupsViewModel
    @AssistedInject
    constructor(
        @Assisted private val podcastIds: List<Long>,
        private val groups: GroupRepository,
        private val podcasts: PodcastRepository,
    ) : ViewModel() {
        private val state = MutableStateFlow(AddToGroupsUiState())
        val uiState = state.asStateFlow()
        private val userMessages = UserMessages()
        val messages = userMessages.flow
        private val initial = mutableMapOf<Long, ToggleableState>()
        private val choices = mutableMapOf<Long, ToggleableState>()
        private var observation: Job? = null
        private var nameValidation: Job? = null

        init {
            reload()
        }

        private fun reload() {
            observation?.cancel()
            initial.clear()
            choices.clear()
            state.update { it.copy(ready = false) }
            observation =
                viewModelScope.launch {
                    // One membership collection serves every selected podcast, including mixed chips.
                    combine(
                        groups.observeGroups(),
                        groups.observeMemberships(),
                        podcasts.observeLibraryTiles(null),
                    ) { rows, memberships, tiles ->
                        val selected = tiles.filter { it.podcastId in podcastIds }
                        val ids = selected.map { it.podcastId }
                        val chips =
                            rows.map { group ->
                                val count = ids.count { group.id in memberships[it].orEmpty() }
                                val selection =
                                    when {
                                        count == 0 -> ToggleableState.Off
                                        count == ids.size -> ToggleableState.On
                                        else -> ToggleableState.Indeterminate
                                    }
                                if (group.id !in choices) initial[group.id] = selection
                                MembershipChip(group, choices[group.id] ?: selection)
                            }
                        Triple(chips, ids.size, selected.singleOrNull()?.displayTitle)
                    }.collect { (chips, count, title) ->
                        state.update {
                            it.copy(
                                chips = chips,
                                podcastCount = count,
                                podcastTitle = title,
                                ready = true,
                            )
                        }
                    }
                }
        }

        fun toggle(groupId: Long) {
            if (state.value.busy) return
            val chip = state.value.chips.find { it.group.id == groupId } ?: return
            val next = if (chip.selection == ToggleableState.On) ToggleableState.Off else ToggleableState.On
            choices[groupId] = next
            val chips =
                state.value.chips.map { row ->
                    if (row.group.id == groupId) row.copy(selection = next) else row
                }
            state.update { it.copy(chips = chips) }
        }

        fun editName(name: String) {
            if (state.value.busy) return
            nameValidation?.cancel()
            state.update { it.copy(name = name, nameError = null) }
            nameValidation =
                viewModelScope.launch {
                    delay(NAME_VALIDATION_DELAY_MS)
                    suspendRunCatching {
                        val error = (groups.checkName(name) as? Outcome.Failure)?.error
                        state.update { it.copy(nameError = error?.let(::nameError)) }
                    }.onFailure { userMessages.post(UiText.Res(Res.string.write_failed)) }
                }
        }

        fun createGroup() {
            val name = state.value.name ?: return
            if (state.value.busy) return
            nameValidation?.cancel()
            state.update { it.copy(busy = true) }
            viewModelScope.launch {
                suspendRunCatching {
                    val checked = groups.checkName(name)
                    val result =
                        when (checked) {
                            is Outcome.Success -> groups.create(GroupDraft(checked.value))
                            is Outcome.Failure -> checked
                        }
                    when (result) {
                        is Outcome.Success -> {
                            initial[result.value] = ToggleableState.Off
                            choices[result.value] = ToggleableState.On
                            state.update { current ->
                                current.copy(
                                    name = null,
                                    nameError = null,
                                    chips =
                                        current.chips.map { row ->
                                            row.copy(selection = choices[row.group.id] ?: row.selection)
                                        },
                                )
                            }
                        }

                        is Outcome.Failure -> {
                            state.update { it.copy(nameError = nameError(result.error)) }
                        }
                    }
                }.onFailure { userMessages.post(UiText.Res(Res.string.write_failed)) }
                state.update { it.copy(busy = false) }
            }
        }

        fun done() {
            if (!state.value.ready || state.value.busy || state.value.doneCount != null) return
            state.update { it.copy(busy = true) }
            viewModelScope.launch {
                suspendRunCatching {
                    // Re-read subscriptions at the write boundary: removed podcasts must never rejoin.
                    val surviving =
                        podcasts
                            .observeLibraryTiles(null)
                            .first()
                            .map { it.podcastId }
                            .toSet()
                            .intersect(podcastIds.toSet())
                    val changed = choices.filter { (id, value) -> value != initial[id] }
                    val add = changed.filterValues { it == ToggleableState.On }.keys
                    val remove = changed.filterValues { it == ToggleableState.Off }.keys
                    when (val result = groups.applyMembership(surviving, add, remove)) {
                        is Outcome.Success -> {
                            state.update { it.copy(doneCount = surviving.size) }
                        }

                        is Outcome.Failure -> {
                            if (result.error == GroupError.NotFound) reload()
                            userMessages.post(UiText.Res(Res.string.membership_changed))
                        }
                    }
                }.onFailure { userMessages.post(UiText.Res(Res.string.write_failed)) }
                state.update { it.copy(busy = false) }
            }
        }

        fun consumeDone(): Int? = state.value.doneCount.also { state.update { it.copy(doneCount = null) } }

        fun acknowledgeMessage(id: Long) = userMessages.shown(id)

        private companion object {
            const val NAME_VALIDATION_DELAY_MS = 300L
        }

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey(Factory::class)
        @ContributesIntoMap(AppScope::class)
        fun interface Factory : ManualViewModelAssistedFactory {
            fun create(podcastIds: List<Long>): AddToGroupsViewModel
        }
    }

private fun nameError(error: GroupError): UiText =
    when (error) {
        GroupError.NameEmpty -> UiText.Res(Res.string.membership_name_empty)
        is GroupError.NameTooLong -> UiText.Res(Res.string.membership_name_too_long, listOf(error.max))
        is GroupError.NameTaken -> UiText.Res(Res.string.membership_name_taken)
        else -> UiText.Res(Res.string.write_failed)
    }
