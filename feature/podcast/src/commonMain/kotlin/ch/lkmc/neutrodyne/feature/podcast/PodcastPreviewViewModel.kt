// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.podcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.AddPodcastResolver
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.SubscribeError
import ch.lkmc.neutrodyne.core.domain.SubscribeUseCase
import ch.lkmc.neutrodyne.core.model.FeedPreview
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.resources.Res
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class PodcastPreviewState(
    val loading: Boolean = true,
    val preview: FeedPreview? = null,
    val fetchError: AddPodcastError? = null,
    val subscribeError: SubscribeError? = null,
    val groups: List<Group> = emptyList(),
    val creatingGroup: Boolean = false,
    val createdGroupId: Long? = null,
    val groupError: UiText? = null,
    val subscribing: Boolean = false,
    val openId: Long? = null,
    val doneId: Long? = null,
)

/** Only the resolver's bounded memory cache is read until the explicit Subscribe action. */
internal class PodcastPreviewViewModel
    @AssistedInject
    constructor(
        @Assisted private val feedUrl: String,
        private val resolver: AddPodcastResolver,
        private val subscribe: SubscribeUseCase,
        private val groups: GroupRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(PodcastPreviewState())
        val state = mutableState.asStateFlow()
        private var resolveJob: Job? = null

        init {
            viewModelScope.launch {
                groups.observeGroups().collect { rows -> mutableState.update { it.copy(groups = rows) } }
            }
            retry()
        }

        fun retry() {
            if (mutableState.value.subscribing) return
            resolveJob?.cancel()
            mutableState.update {
                it.copy(
                    loading = true,
                    preview = null,
                    fetchError = null,
                    subscribeError = null,
                    openId = null,
                )
            }
            resolveJob =
                viewModelScope.launch {
                    val result = resolver.preview(feedUrl)
                    ensureActive()
                    mutableState.update {
                        when (result) {
                            is Outcome.Success -> {
                                it.copy(
                                    loading = false,
                                    preview = result.value,
                                    openId =
                                        result.value.alreadySubscribed
                                            ?.takeIf { hit -> hit.exact }
                                            ?.podcastId,
                                )
                            }

                            is Outcome.Failure -> {
                                it.copy(loading = false, fetchError = result.error)
                            }
                        }
                    }
                }
        }

        fun createGroup(name: String) {
            if (mutableState.value.creatingGroup || mutableState.value.subscribing) return
            mutableState.update { it.copy(creatingGroup = true, groupError = null) }
            viewModelScope.launch {
                suspendRunCatching {
                    when (val result = groups.create(GroupDraft(name))) {
                        is Outcome.Success -> {
                            mutableState.update { it.copy(createdGroupId = result.value) }
                        }

                        is Outcome.Failure -> {
                            mutableState.update {
                                it.copy(
                                    groupError =
                                        when (val error = result.error) {
                                            GroupError.NameEmpty -> {
                                                UiText.Res(Res.string.membership_name_empty)
                                            }

                                            is GroupError.NameTaken -> {
                                                UiText.Res(Res.string.membership_name_taken)
                                            }

                                            is GroupError.NameTooLong -> {
                                                UiText.Res(
                                                    Res.string.membership_name_too_long,
                                                    listOf(error.max),
                                                )
                                            }

                                            else -> {
                                                UiText.Res(Res.string.write_failed)
                                            }
                                        },
                                )
                            }
                        }
                    }
                }.onFailure {
                    mutableState.update { it.copy(groupError = UiText.Res(Res.string.write_failed)) }
                }
                mutableState.update { it.copy(creatingGroup = false) }
            }
        }

        fun consumeCreatedGroup() {
            mutableState.update { it.copy(createdGroupId = null) }
        }

        fun subscribe(groupIds: Set<Long>) {
            val current = mutableState.value
            val preview = current.preview ?: return
            if (current.loading || current.subscribing || current.creatingGroup || current.openId != null) return
            mutableState.update { it.copy(subscribing = true, subscribeError = null) }
            viewModelScope.launch {
                val result = subscribe(preview.previewId, groupIds)
                mutableState.update {
                    when (result) {
                        is Outcome.Success -> {
                            it.copy(subscribing = false, doneId = result.value)
                        }

                        is Outcome.Failure -> {
                            it.copy(
                                subscribing = false,
                                subscribeError =
                                    result.error.takeUnless { error ->
                                        error is SubscribeError.AlreadySubscribed
                                    },
                                openId = (result.error as? SubscribeError.AlreadySubscribed)?.podcastId,
                            )
                        }
                    }
                }
            }
        }

        fun consumeDone(): Long? {
            val id = mutableState.value.doneId ?: return null
            mutableState.update { it.copy(doneId = null) }
            return id
        }

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey(Factory::class)
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(feedUrl: String): PodcastPreviewViewModel
        }
    }
