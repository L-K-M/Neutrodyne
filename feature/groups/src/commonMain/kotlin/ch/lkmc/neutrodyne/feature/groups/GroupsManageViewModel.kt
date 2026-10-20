// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.DeletedGroupToken
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.SuggestedGroups
import ch.lkmc.neutrodyne.core.model.FeedSourceRef
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupEdit
import ch.lkmc.neutrodyne.core.model.GroupMosaicMember
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.navigation.GroupEditKey
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.UserMessage
import ch.lkmc.neutrodyne.core.ui.UserMessages
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.groups_manage_changed
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

internal data class GroupsManageUiState(
    val groups: List<Group> = emptyList(),
    val mosaics: Map<Long, List<GroupMosaicMember>> = emptyMap(),
    val loaded: Boolean = false,
    val loadFailed: Boolean = false,
    val busy: Boolean = false,
    val draggingId: Long? = null,
    val navigation: GroupEditKey? = null,
    val deleted: GroupEditDone.Deleted? = null,
    val messages: ImmutableList<UserMessage> = persistentListOf(),
)

internal enum class GroupMove { UP, DOWN, TOP }

@ViewModelKey(GroupsManageViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
@Suppress("TooManyFunctions") // One screen's input handlers share its drag and write state.
internal class GroupsManageViewModel(
    private val repository: GroupRepository,
    podcasts: PodcastRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val suggestedGroups = SuggestedGroups(podcasts, repository, settings, viewModelScope)

    private val state = MutableStateFlow(GroupsManageUiState())
    val uiState = state.asStateFlow()
    private val messages = UserMessages()
    private var latest: List<Group> = emptyList()
    private var preview: List<Long>? = null
    private var dragStartOrder: List<Long> = emptyList()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch { messages.flow.collect { list -> state.update { it.copy(messages = list) } } }
        retry()
    }

    suspend fun openSuggested(uuid: String): Boolean {
        val result =
            suspendRunCatching {
                settings.set(
                    AppearanceSettingKeys.FEEDS_SELECTED_SOURCE,
                    FeedSourceRef.Group(uuid).encode(),
                )
            }.getOrNull()
        if (result is Outcome.Success) return true
        messages.post(UiText.Res(Res.string.write_failed))
        return false
    }

    fun retry() {
        loadJob?.cancel()
        state.update { it.copy(loadFailed = false) }
        loadJob =
            viewModelScope.launch {
                suspendRunCatching {
                    combine(repository.observeGroups(), repository.observeMosaics()) { groups, mosaics ->
                        groups to mosaics
                    }.collect { (groups, mosaics) ->
                        latest = groups
                        val order = preview
                        if (!state.value.busy && order != null && order.toSet() != groups.map { it.id }.toSet()) {
                            clearDrag()
                            changed()
                        }
                        state.update { it.copy(loaded = true, loadFailed = false, mosaics = mosaics) }
                        publishGroups()
                    }
                }.onFailure {
                    clearDrag()
                    state.update { it.copy(loadFailed = true) }
                    publishGroups()
                }
            }
    }

    fun startDrag(id: Long) {
        if (!canWrite() || state.value.draggingId != null || latest.none { it.id == id }) return
        preview = state.value.groups.map { it.id }
        dragStartOrder = preview.orEmpty()
        state.update { it.copy(draggingId = id) }
    }

    fun dragOver(id: Long) {
        val moving = state.value.draggingId ?: return
        val order = preview.orEmpty()
        val target = order.indexOf(id)
        if (target < 0 || id == moving || state.value.busy) return
        preview =
            order.toMutableList().apply {
                remove(moving)
                add(target, moving)
            }
        publishGroups()
    }

    fun drop() {
        val id = state.value.draggingId ?: return
        val order = preview.orEmpty()
        if (order == dragStartOrder || order == latest.map { it.id }) {
            cancelDrag()
            return
        }
        // Rebase only this move onto the latest flow, preserving unrelated concurrent reorders.
        val next = order.drop(order.indexOf(id) + 1).firstOrNull()
        val requested =
            latest.map { it.id }.toMutableList().apply {
                remove(id)
                // A neighbour that vanished meanwhile anchors nothing: append instead of crashing.
                add(next?.let(::indexOf)?.takeIf { it >= 0 } ?: size, id)
            }
        preview = requested
        publishGroups()
        write {
            val result = repository.reorder(requested)
            if (result is Outcome.Failure) changed()
        }
    }

    fun cancelDrag() {
        if (state.value.busy) return
        clearDrag()
        publishGroups()
    }

    fun move(
        id: Long,
        direction: GroupMove,
    ) {
        val index = latest.indexOfFirst { it.id == id }
        if (!canWrite() || state.value.draggingId != null || index < 0) return
        val target =
            when (direction) {
                GroupMove.UP -> index - 1
                GroupMove.DOWN -> index + 1
                GroupMove.TOP -> 0
            }
        if (target !in latest.indices || target == index) return
        startDrag(id)
        dragOver(latest[target].id)
        drop()
    }

    fun showAsTab(
        id: Long,
        show: Boolean,
    ) {
        if (state.value.draggingId != null) return
        write {
            if (repository.update(id, GroupEdit.ShowAsTab(show)) is Outcome.Failure) changed()
        }
    }

    fun delete(id: Long) {
        if (state.value.draggingId != null || state.value.deleted != null) return
        val group = latest.find { it.id == id } ?: return
        write {
            val started = TimeSource.Monotonic.markNow()
            when (val result = repository.delete(id)) {
                is Outcome.Success -> {
                    state.update {
                        it.copy(deleted = GroupEditDone.Deleted(result.value, group.name, started))
                    }
                }

                is Outcome.Failure -> {
                    changed()
                }
            }
        }
    }

    suspend fun undoDelete(token: DeletedGroupToken): Outcome<Long, GroupError> = repository.undoDelete(token)

    fun consumeDeleted(): GroupEditDone.Deleted? = state.value.deleted.also { state.update { it.copy(deleted = null) } }

    fun openEditor(id: Long? = null) {
        if (state.value.busy || state.value.draggingId != null) return
        if (id != null && latest.none { it.id == id }) return
        state.update { it.copy(navigation = GroupEditKey(id)) }
    }

    fun consumeNavigation(): GroupEditKey? = state.value.navigation.also { state.update { it.copy(navigation = null) } }

    fun onMessageShown(id: Long) = messages.shown(id)

    private fun canWrite() = state.value.loaded && !state.value.loadFailed && !state.value.busy

    private fun clearDrag() {
        preview = null
        state.update { it.copy(draggingId = null) }
    }

    private fun publishGroups() {
        val byId = latest.associateBy { it.id }
        val groups = preview?.mapNotNull(byId::get) ?: latest
        state.update { it.copy(groups = groups) }
    }

    private fun changed() = messages.post(UiText.Res(Res.string.groups_manage_changed))

    private fun write(block: suspend () -> Unit) {
        if (!canWrite()) return
        state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                suspendRunCatching {
                    block()
                    // Read after the write so stale flow emissions cannot snap the preview back.
                    latest = repository.observeGroups().first()
                }.onFailure { messages.post(UiText.Res(Res.string.write_failed)) }
            } finally {
                clearDrag()
                state.update { it.copy(busy = false) }
                publishGroups()
            }
        }
    }
}
