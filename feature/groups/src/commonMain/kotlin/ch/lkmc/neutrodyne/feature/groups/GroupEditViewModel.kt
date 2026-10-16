// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.DeletedGroupToken
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.GroupEdit
import ch.lkmc.neutrodyne.core.model.GroupPalette
import ch.lkmc.neutrodyne.core.model.LibraryTile
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal data class GroupEditDraft(
    val name: String = "",
    val colorArgb: Int? = null,
    val iconKey: String? = null,
    val playOrder: FeedOrder = FeedOrder.NEWEST_FIRST,
    val showAsTab: Boolean = true,
    val memberIds: Set<Long> = emptySet(),
) {
    fun groupDraft(): GroupDraft = GroupDraft(name, colorArgb, iconKey, playOrder, showAsTab)

    // Only primitives enter SavedStateHandle; neither Room models nor process-local undo tokens do.
    fun save(): List<String> =
        listOf(name, colorArgb?.toString().orEmpty(), iconKey.orEmpty(), playOrder.name, showAsTab.toString()) +
            memberIds.map(Long::toString)

    companion object {
        private const val FIELD_COUNT = 5

        fun restore(values: List<String>): GroupEditDraft {
            val (name, color, icon, playOrder, showAsTab) = values
            return GroupEditDraft(
                name,
                color.toIntOrNull(),
                icon.ifEmpty { null },
                FeedOrder.valueOf(playOrder),
                showAsTab.toBoolean(),
                values.drop(FIELD_COUNT).map(String::toLong).toSet(),
            )
        }
    }
}

internal sealed interface GroupEditDone {
    data object Saved : GroupEditDone

    data object Gone : GroupEditDone

    data class Deleted(
        val token: DeletedGroupToken,
        val name: String,
        val started: TimeMark,
    ) : GroupEditDone
}

internal data class GroupEditUiState(
    val draft: GroupEditDraft = GroupEditDraft(),
    val loaded: Boolean = false,
    val loadFailed: Boolean = false,
    val membersFailed: Boolean = false,
    val busy: Boolean = false,
    val nameError: GroupError? = null,
    val tiles: List<LibraryTile> = emptyList(),
    val search: String = "",
    val dirty: Boolean = false,
    val done: GroupEditDone? = null,
    val messages: ImmutableList<UserMessage> = persistentListOf(),
)

@Suppress("TooManyFunctions") // Keep this editor's input handlers and lifecycle together.
internal class GroupEditViewModel
    @AssistedInject
    constructor(
        @Assisted private val groupId: Long?,
        @Assisted private val savedState: SavedStateHandle,
        private val groups: GroupRepository,
        private val podcasts: PodcastRepository,
    ) : ViewModel() {
        private val state = MutableStateFlow(GroupEditUiState())
        val uiState: StateFlow<GroupEditUiState> = state.asStateFlow()
        private val messages = UserMessages()
        private var baseline: GroupEditDraft? = null
        private var validation: Job? = null
        private var membersJob: Job? = null

        init {
            viewModelScope.launch { messages.flow.collect { list -> state.update { it.copy(messages = list) } } }
            loadMembers()
            load()
        }

        fun load() {
            if (state.value.loaded || state.value.busy) return
            state.update { it.copy(busy = true, loadFailed = false) }
            viewModelScope.launch {
                suspendRunCatching {
                    val initial =
                        if (groupId == null) {
                            GroupEditDraft(
                                colorArgb = GroupPalette.nextColor(groups.observeGroups().first().map { it.colorArgb }),
                            )
                        } else {
                            val group = groups.observeGroup(groupId).first()
                            if (group == null) {
                                state.update { it.copy(done = GroupEditDone.Gone) }
                                return@suspendRunCatching
                            }
                            GroupEditDraft(
                                group.name,
                                group.colorArgb,
                                group.iconKey,
                                group.playOrder,
                                group.showAsTab,
                                groups.observeMemberIds(groupId).first(),
                            )
                        }
                    baseline = savedState.get<List<String>>(BASELINE)?.let(GroupEditDraft::restore) ?: initial
                    val draft = savedState.get<List<String>>(DRAFT)?.let(GroupEditDraft::restore) ?: initial
                    state.update { it.copy(loaded = true) }
                    setDraft(draft)
                    if (draft.name.isNotEmpty() || draft != baseline) validateName()
                }.onFailure {
                    state.update { it.copy(loadFailed = true) }
                    reportFailure(it)
                }
                state.update { it.copy(busy = false) }
            }
        }

        fun retry() {
            load()
            loadMembers()
        }

        private fun loadMembers() {
            membersJob?.cancel()
            state.update { it.copy(membersFailed = false) }
            membersJob =
                viewModelScope.launch {
                    podcasts
                        .observeLibraryTiles(null)
                        .catch {
                            state.update { it.copy(membersFailed = true) }
                            reportFailure(it)
                        }.collect { tiles -> state.update { it.copy(tiles = tiles) } }
                }
        }

        fun edit(draft: GroupEditDraft) {
            if (!state.value.loaded || state.value.busy || state.value.done != null) return
            val nameChanged = draft.name != state.value.draft.name
            setDraft(draft)
            if (nameChanged) {
                state.update { it.copy(nameError = null) }
                validateName()
            }
        }

        fun search(value: String) {
            state.update { it.copy(search = value) }
        }

        fun toggleMember(id: Long) {
            val draft = state.value.draft
            edit(draft.copy(memberIds = if (id in draft.memberIds) draft.memberIds - id else draft.memberIds + id))
        }

        private fun setDraft(draft: GroupEditDraft) {
            savedState[BASELINE] = baseline?.save()
            savedState[DRAFT] = draft.save()
            state.update { it.copy(draft = draft, dirty = draft != baseline) }
        }

        private fun validateName() {
            validation?.cancel()
            val name = state.value.draft.name
            validation =
                viewModelScope.launch {
                    delay(NAME_DEBOUNCE_MS)
                    suspendRunCatching { groups.checkName(name, groupId) }
                        .onSuccess { result ->
                            if (state.value.draft.name == name) {
                                state.update { it.copy(nameError = (result as? Outcome.Failure)?.error) }
                            }
                        }.onFailure(::reportFailure)
                }
        }

        fun save() =
            write {
                validation?.cancel()
                val draft = state.value.draft
                val checked = groups.checkName(draft.name, groupId)
                if (checked is Outcome.Failure) {
                    reportError(checked.error)
                    return@write
                }
                if (groupId == null) {
                    val result = groups.create(draft.groupDraft(), draft.memberIds)
                    if (result is Outcome.Failure) {
                        reportError(result.error)
                        return@write
                    }
                } else {
                    // Rename first: a conflicting name must not write the look, flags or memberships.
                    // Keep feed order owned by group settings, including changes made while this editor was open.
                    val current = groups.observeGroup(groupId).first()
                    if (current == null) {
                        reportError(GroupError.NotFound)
                        return@write
                    }
                    val edits =
                        listOf(
                            GroupEdit.Rename(draft.name),
                            GroupEdit.Look(draft.colorArgb, draft.iconKey),
                            GroupEdit.Orders(current.feedOrder, draft.playOrder),
                            GroupEdit.ShowAsTab(draft.showAsTab),
                        )
                    for (edit in edits) {
                        val result = groups.update(groupId, edit)
                        if (result is Outcome.Failure) {
                            reportError(result.error)
                            return@write
                        }
                    }
                    val members = groups.setMembers(groupId, draft.memberIds)
                    if (members is Outcome.Failure) {
                        reportError(members.error)
                        return@write
                    }
                }
                state.update { it.copy(done = GroupEditDone.Saved) }
            }

        fun delete() {
            val id = groupId ?: return
            write {
                val started = TimeSource.Monotonic.markNow()
                val name =
                    groups
                        .observeGroup(id)
                        .first()
                        ?.name
                        .orEmpty()
                when (val result = groups.delete(id)) {
                    is Outcome.Success -> {
                        state.update {
                            it.copy(
                                done = GroupEditDone.Deleted(result.value, name, started),
                            )
                        }
                    }

                    is Outcome.Failure -> {
                        reportError(result.error)
                    }
                }
            }
        }

        // Called by the route's surviving snackbar effect, after this entry's ViewModel is cleared.
        suspend fun undoDelete(token: DeletedGroupToken): Outcome<Long, GroupError> = groups.undoDelete(token)

        fun consumeDone(): GroupEditDone? {
            val done = state.value.done
            state.update { it.copy(done = null) }
            return done
        }

        fun onMessageShown(id: Long) = messages.shown(id)

        private fun write(block: suspend () -> Unit) {
            if (!state.value.loaded || state.value.busy || state.value.done != null) return
            state.update { it.copy(busy = true) }
            viewModelScope.launch {
                try {
                    suspendRunCatching { block() }.onFailure(::reportFailure)
                } finally {
                    state.update { it.copy(busy = false) }
                }
            }
        }

        private fun reportError(error: GroupError) {
            if (error is GroupError.NameEmpty || error is GroupError.NameTooLong || error is GroupError.NameTaken) {
                state.update { it.copy(nameError = error) }
            } else {
                messages.post(UiText.Res(Res.string.write_failed))
            }
        }

        private fun reportFailure(error: Throwable) {
            Log.w("GroupEdit", error) { "Group edit failed" }
            messages.post(UiText.Res(Res.string.write_failed))
        }

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey(Factory::class)
        @ContributesIntoMap(AppScope::class)
        fun interface Factory : ManualViewModelAssistedFactory {
            fun create(
                groupId: Long?,
                savedState: SavedStateHandle,
            ): GroupEditViewModel
        }

        private companion object {
            const val NAME_DEBOUNCE_MS = 300L
            const val DRAFT = "groupEditDraft"
            const val BASELINE = "groupEditBaseline"
        }
    }
