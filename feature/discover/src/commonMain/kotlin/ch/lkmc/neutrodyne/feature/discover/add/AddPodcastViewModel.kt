// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.discover.add

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.AddPodcastResolver
import ch.lkmc.neutrodyne.core.domain.AddResolution
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.SubscribeError
import ch.lkmc.neutrodyne.core.domain.SubscribeUseCase
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedCandidate
import ch.lkmc.neutrodyne.core.model.FeedPreview
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.membership_name_empty
import ch.lkmc.neutrodyne.core.ui.resources.membership_name_taken
import ch.lkmc.neutrodyne.core.ui.resources.membership_name_too_long
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The sheet's state (08 Add podcast sheet): [step] is the resolution pipeline and [done] the
 * terminal subscribe success the route turns into pop + "Subscribed to {title}" snackbar.
 */
@Immutable
public data class AddPodcastUiState(
    val step: AddSheetStep = AddSheetStep.Input(),
    val done: SubscribedPodcast? = null,
    val groups: List<Group> = emptyList(),
    val createdGroupId: Long? = null,
    val creatingGroup: Boolean = false,
    val groupError: UiText? = null,
    /**
     * The identity of the newest resolve/subscribe attempt (UI review round 2). Completions
     * tagged with an older generation are dropped; the route closes only while [done]'s
     * generation is still this one.
     */
    val operationGeneration: Int = 0,
)

/**
 * The terminal state: the sheet closes and the snackbar's "Open" leads to `PodcastKey`.
 * [operationGeneration] is the subscribe attempt that produced it.
 */
public data class SubscribedPodcast(
    val podcastId: Long,
    val title: String,
    val operationGeneration: Int,
)

/**
 * One sheet state of 08's `AddResolution` table. [Input] keeps the field editable — [error] is
 * the failure line under it and [auth] shows `AuthRequired`'s username/password fields.
 * [Preview] carries the card plus subscribe's own progress, error and race-time dedupe.
 */
public sealed interface AddSheetStep {
    public data class Input(
        val error: UiText? = null,
        val auth: Boolean = false,
        /** W5 renders the explicit consent action; it must never silently retry. */
        val lookupRequired: AddResolution.LookupRequired? = null,
    ) : AddSheetStep

    /** "Looking up…" — the progress line; `cancelResolve` returns to [Input]. */
    public data object Resolving : AddSheetStep

    /** `Choose`: the page offered several feeds; picking one re-runs `resolve(candidate.url)`. */
    public data class Choosing(
        val candidates: List<FeedCandidate>,
    ) : AddSheetStep

    /** `Feed(preview)`; [alreadySubscribedId] is subscribe's own dedupe hit. */
    public data class Preview(
        val preview: FeedPreview,
        val subscribing: Boolean = false,
        val subscribeError: UiText? = null,
        val alreadySubscribedId: Long? = null,
    ) : AddSheetStep
}

/**
 * The add-podcast sheet's ViewModel (08 Add podcast sheet; 03 Add podcast flow). The screen keeps
 * the field text (`rememberSaveable`); this holds the resolution state and re-resolves on demand —
 * after process death the restored field fires [resolve] again (previews are memory-only, D24).
 *
 * Chosen groups are saved by the sheet and passed into the subscribe transaction.
 */
@ViewModelKey(AddPodcastViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
public class AddPodcastViewModel(
    private val resolver: AddPodcastResolver,
    private val subscribeUseCase: SubscribeUseCase,
    private val groups: GroupRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AddPodcastUiState())
    public val uiState: StateFlow<AddPodcastUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            groups.observeGroups().collect { rows -> mutableState.update { it.copy(groups = rows) } }
        }
    }

    public fun createGroup(name: String) {
        if (mutableState.value.creatingGroup) return
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

                                        is GroupError.NameTooLong -> {
                                            UiText.Res(
                                                Res.string.membership_name_too_long,
                                                listOf(error.max),
                                            )
                                        }

                                        is GroupError.NameTaken -> {
                                            UiText.Res(Res.string.membership_name_taken)
                                        }

                                        else -> {
                                            UiText.Res(Res.string.write_failed)
                                        }
                                    },
                            )
                        }
                    }
                }
            }.onFailure { mutableState.update { it.copy(groupError = UiText.Res(Res.string.write_failed)) } }
            mutableState.update { it.copy(creatingGroup = false) }
        }
    }

    public fun consumeCreatedGroup() {
        mutableState.update { it.copy(createdGroupId = null) }
    }

    private var resolveJob: Job? = null

    /** The input the current [AddSheetStep] reflects; a repeat resolve of it is a no-op. */
    private var resolvedInput: String? = null

    /**
     * Allocates `operationGeneration`s. Every new resolve/subscribe attempt — and every edit to
     * the address field — supersedes the previous operation: only the latest generation's
     * completion may write `done`, clear a `subscribing` flag or land a preview — the previewId
     * cannot stand in for it because the cache keys previews on the feed URL, which same-feed
     * retries share.
     */
    private var operation = 0

    /** Bumps the operation generation and records it (plus [update]) on the state. */
    private inline fun nextGeneration(crossinline update: (AddPodcastUiState) -> AddPodcastUiState): Int {
        val generation = ++operation
        mutableState.update { update(it).copy(operationGeneration = generation, done = null) }
        return generation
    }

    /**
     * Resolves [input] (03's pipeline). The same input re-submitted while its resolution still
     * stands (a recomposition re-fire) is skipped; in a failure step it retries — the failure's
     * Retry button and a repeated Enter are the same call.
     */
    public fun resolve(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return
        if (trimmed == resolvedInput && mutableState.value.step !is AddSheetStep.Input) return
        resolvedInput = trimmed
        runResolve { resolver.resolve(trimmed) }
    }

    /** The `AuthRequired` retry (03 Basic auth): the same input, now with credentials. */
    public fun resolveWithCredentials(credentials: BasicCredentials) {
        val input = resolvedInput ?: return
        runResolve { resolver.resolve(input, credentials) }
    }

    /**
     * Editing the address drops a stale preview/candidate list, cancels a running lookup and
     * supersedes the in-flight operation: a subscribe launched for the old address must not let
     * a late success write `done` and close a sheet whose field now holds new text (UI review
     * round 3).
     */
    public fun onInputChanged(text: String) {
        if (text.trim() == resolvedInput) return
        resolveJob?.cancel()
        nextGeneration { it.copy(step = AddSheetStep.Input()) }
    }

    /**
     * The route's acknowledgement of the terminal `done` (01: one-shot events are state with
     * acknowledgement): reads and clears it atomically, so a recreated effect finds nothing and
     * cannot replay the pop over the screen beneath the sheet. Returns `done` only while its
     * generation still owns the sheet — a newer resolve may have started after it was collected
     * (UI review round 2).
     */
    public fun consumeDone(): SubscribedPodcast? {
        var consumed: SubscribedPodcast? = null
        mutableState.update { state ->
            consumed = state.done?.takeIf { it.operationGeneration == state.operationGeneration }
            if (consumed == null) state else state.copy(done = null)
        }
        return consumed
    }

    /** "Looking up…" is cancellable (08) — back to the editable field. */
    public fun cancelResolve() {
        resolveJob?.cancel()
        mutableState.update { it.copy(step = AddSheetStep.Input()) }
    }

    /**
     * 03 Subscribe transaction: nothing is written until this runs. `AlreadySubscribed` surfaces
     * as the "Already subscribed — Open" affordance, everything else as an inline error. The
     * attempt takes an operation generation: a completion arriving after the sheet moved to a
     * newer operation is dropped rather than restoring the captured step over it or closing
     * the sheet under it.
     */
    public fun subscribe(groupIds: Set<Long> = emptySet()) {
        val step = mutableState.value.step as? AddSheetStep.Preview ?: return
        if (step.subscribing || step.alreadySubscribedId != null || mutableState.value.creatingGroup) return
        val generation =
            nextGeneration { it.copy(step = step.copy(subscribing = true, subscribeError = null)) }
        viewModelScope.launch {
            when (val outcome = subscribeUseCase(step.preview.previewId, groupIds)) {
                is Outcome.Success -> {
                    mutableState.update { state ->
                        if (state.operationGeneration != generation) return@update state
                        state.copy(
                            done =
                                SubscribedPodcast(outcome.value, step.preview.title, generation),
                        )
                    }
                }

                is Outcome.Failure -> {
                    mutableState.update { state ->
                        if (state.operationGeneration != generation) return@update state
                        val current = state.step as? AddSheetStep.Preview ?: return@update state
                        state.copy(
                            step =
                                when (val error = outcome.error) {
                                    is SubscribeError.AlreadySubscribed -> {
                                        current.copy(
                                            subscribing = false,
                                            alreadySubscribedId = error.podcastId,
                                        )
                                    }

                                    else -> {
                                        current.copy(
                                            subscribing = false,
                                            subscribeError = SubscribeErrorText.describe(error),
                                        )
                                    }
                                },
                        )
                    }
                }
            }
        }
    }

    private fun runResolve(block: suspend () -> AddResolution) {
        resolveJob?.cancel()
        val generation = nextGeneration { it.copy(step = AddSheetStep.Resolving) }
        resolveJob =
            viewModelScope.launch {
                val resolution = block()
                // A result of a superseded resolve is dropped: a newer operation owns the step.
                ensureActive()
                apply(generation, resolution)
            }
    }

    private fun apply(
        generation: Int,
        resolution: AddResolution,
    ) {
        mutableState.update { state ->
            if (state.operationGeneration != generation) return@update state
            state.copy(
                step =
                    when (resolution) {
                        is AddResolution.LookupRequired -> {
                            AddSheetStep.Input(lookupRequired = resolution)
                        }

                        is AddResolution.Feed -> {
                            AddSheetStep.Preview(resolution.preview)
                        }

                        is AddResolution.Choose -> {
                            AddSheetStep.Choosing(resolution.candidates)
                        }

                        is AddResolution.Failure -> {
                            AddSheetStep.Input(
                                error = AddPodcastText.describe(resolution.error),
                                auth = resolution.error is AddPodcastError.AuthRequired,
                            )
                        }
                    },
            )
        }
    }
}
