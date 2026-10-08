// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.discover.add

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.AddPodcastResolver
import ch.lkmc.neutrodyne.core.domain.AddResolution
import ch.lkmc.neutrodyne.core.domain.SubscribeError
import ch.lkmc.neutrodyne.core.domain.SubscribeUseCase
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedCandidate
import ch.lkmc.neutrodyne.core.model.FeedPreview
import ch.lkmc.neutrodyne.core.ui.UiText
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
)

/** The terminal state: the sheet closes and the snackbar's "Open" leads to `PodcastKey`. */
public data class SubscribedPodcast(
    val podcastId: Long,
    val title: String,
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
 * Group chips arrive with M2's group feed; M1a subscribes with an empty `groupIds` set (deviation
 * recorded in 08, 2026-10-07).
 */
@ViewModelKey(AddPodcastViewModel::class)
@ContributesIntoMap(AppScope::class)
@Inject
public class AddPodcastViewModel(
    private val resolver: AddPodcastResolver,
    private val subscribeUseCase: SubscribeUseCase,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AddPodcastUiState())
    public val uiState: StateFlow<AddPodcastUiState> = mutableState.asStateFlow()

    private var resolveJob: Job? = null

    /** The input the current [AddSheetStep] reflects; a repeat resolve of it is a no-op. */
    private var resolvedInput: String? = null

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

    /** Editing the address drops a stale preview/candidate list and cancels a running lookup. */
    public fun onInputChanged(text: String) {
        if (text.trim() == resolvedInput) return
        resolveJob?.cancel()
        mutableState.update { it.copy(step = AddSheetStep.Input()) }
    }

    /** "Looking up…" is cancellable (08) — back to the editable field. */
    public fun cancelResolve() {
        resolveJob?.cancel()
        mutableState.update { it.copy(step = AddSheetStep.Input()) }
    }

    /**
     * 03 Subscribe transaction: nothing is written until this runs. `AlreadySubscribed` surfaces
     * as the "Already subscribed — Open" affordance, everything else as an inline error. The
     * operation is tagged by `previewId`: a result arriving after the sheet moved to a newer
     * preview is dropped rather than restoring the captured step over it.
     */
    public fun subscribe() {
        val step = mutableState.value.step as? AddSheetStep.Preview ?: return
        if (step.subscribing || step.alreadySubscribedId != null) return
        mutableState.update { it.copy(step = step.copy(subscribing = true, subscribeError = null)) }
        viewModelScope.launch {
            when (val outcome = subscribeUseCase(step.preview.previewId, emptySet())) {
                is Outcome.Success -> {
                    mutableState.update {
                        it.copy(done = SubscribedPodcast(outcome.value, step.preview.title))
                    }
                }

                is Outcome.Failure -> {
                    mutableState.update { state ->
                        val current = state.step as? AddSheetStep.Preview
                        if (current?.preview?.previewId != step.preview.previewId) {
                            return@update state
                        }
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
        resolveJob =
            viewModelScope.launch {
                mutableState.update { it.copy(step = AddSheetStep.Resolving) }
                val resolution = block()
                // A result of a superseded resolve is dropped: a newer operation owns the step.
                ensureActive()
                apply(resolution)
            }
    }

    private fun apply(resolution: AddResolution) {
        mutableState.update { state ->
            state.copy(
                step =
                    when (resolution) {
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
