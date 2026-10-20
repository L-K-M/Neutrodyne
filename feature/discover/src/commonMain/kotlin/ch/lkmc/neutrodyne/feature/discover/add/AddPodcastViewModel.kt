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
import ch.lkmc.neutrodyne.core.domain.RssAlternative
import ch.lkmc.neutrodyne.core.domain.SubscribeError
import ch.lkmc.neutrodyne.core.domain.SubscribeUseCase
import ch.lkmc.neutrodyne.core.domain.YouTubeChannelRepository
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedCandidate
import ch.lkmc.neutrodyne.core.model.FeedPreview
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.YouTubeVariantBits
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.membership_name_empty
import ch.lkmc.neutrodyne.core.ui.resources.membership_name_taken
import ch.lkmc.neutrodyne.core.ui.resources.membership_name_too_long
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import ch.lkmc.neutrodyne.youtube.api.ChannelResolution
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.youtube.api.YouTubeChannelResolver
import ch.lkmc.neutrodyne.youtube.api.YouTubeFeedUrls
import ch.lkmc.neutrodyne.youtube.api.YtRef
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
    /** Gates the explicit YouTube channel-search affordance on the Input step (W10 D2). */
    val channelSearch: Boolean = false,
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
    public data class Channel(
        val resolution: ChannelResolution.Resolved,
        val variants: Int,
        val alternative: RssAlternative? = null,
        val subscribing: Boolean = false,
        val subscribeError: UiText? = null,
        val alreadySubscribedId: Long? = null,
    ) : AddSheetStep

    public data class ChannelFailure(
        val result: ChannelResolution,
    ) : AddSheetStep

    public data class Input(
        val error: UiText? = null,
        val auth: Boolean = false,
        val searchQuery: String? = null,
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
    private val channelResolver: YouTubeChannelResolver,
    private val channels: YouTubeChannelRepository,
    private val capabilities: YouTubeCapabilitiesSource,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AddPodcastUiState())
    public val uiState: StateFlow<AddPodcastUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            groups.observeGroups().collect { rows -> mutableState.update { it.copy(groups = rows) } }
        }
        viewModelScope.launch {
            capabilities.capabilities.collect { caps ->
                mutableState.update { it.copy(channelSearch = caps.channelSearch) }
            }
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
        val step = mutableState.value.step
        if (trimmed == resolvedInput && step !is AddSheetStep.Input && step !is AddSheetStep.ChannelFailure) return
        resolvedInput = trimmed
        runResolve { resolver.resolve(trimmed) }
    }

    /** The `AuthRequired` retry (03 Basic auth): the same input, now with credentials. */
    public fun lookupOnce() {
        val required = (mutableState.value.step as? AddSheetStep.Input)?.lookupRequired ?: return
        runResolve { resolver.lookupOnce(required.input) }
    }

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
        resolvedInput = null
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
        resolvedInput = null
        nextGeneration { it.copy(step = AddSheetStep.Input()) }
    }

    /**
     * 03 Subscribe transaction: nothing is written until this runs. `AlreadySubscribed` surfaces
     * as the "Already subscribed — Open" affordance, everything else as an inline error. The
     * attempt takes an operation generation: a completion arriving after the sheet moved to a
     * newer operation is dropped rather than restoring the captured step over it or closing
     * the sheet under it.
     */
    public fun subscribe(groupIds: Set<Long> = emptySet()) {
        if (mutableState.value.step is AddSheetStep.Channel) {
            subscribeChannel(groupIds)
            return
        }
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

    public fun usePodcast() {
        val step = mutableState.value.step as? AddSheetStep.Channel ?: return
        val url = step.alternative?.feedUrl ?: return
        resolve(url)
    }

    public fun subscribeOwner() {
        val result =
            (mutableState.value.step as? AddSheetStep.ChannelFailure)?.result
                as? ChannelResolution.PlaylistUnsupported ?: return
        result.ownerChannelId?.let { resolve(YouTubeFeedUrls.channelPage(it)) }
    }

    private suspend fun resolveChannel(
        generation: Int,
        ref: YtRef,
    ) {
        val result = channelResolver.resolve(ref)
        kotlin.coroutines.coroutineContext.ensureActive()
        if (mutableState.value.operationGeneration != generation) return
        if (result !is ChannelResolution.Resolved) {
            mutableState.update { state ->
                if (state.operationGeneration != generation) {
                    state
                } else {
                    state.copy(step = AddSheetStep.ChannelFailure(result))
                }
            }
            return
        }
        val duplicate = channels.subscribedPodcastId(result.channelId)
        val variants = (ref as? YtRef.Channel)?.variantsHint ?: YouTubeVariantBits.LONG_FORM
        mutableState.update { state ->
            if (state.operationGeneration != generation) {
                state
            } else {
                state.copy(step = AddSheetStep.Channel(result, variants, alreadySubscribedId = duplicate))
            }
        }
        // This optional request never holds the preview or Subscribe button in a loading state.
        result.title?.let { title ->
            val alternative = suspendRunCatching { channels.findRssAlternative(title) }.getOrNull()
            mutableState.update { state ->
                val step = state.step as? AddSheetStep.Channel
                if (state.operationGeneration != generation || step == null) {
                    state
                } else {
                    state.copy(step = step.copy(alternative = alternative))
                }
            }
        }
    }

    private fun subscribeChannel(groupIds: Set<Long>) {
        val step = mutableState.value.step as? AddSheetStep.Channel ?: return
        if (step.subscribing || step.alreadySubscribedId != null || mutableState.value.creatingGroup) return
        resolveJob?.cancel()
        val generation = nextGeneration { it.copy(step = step.copy(subscribing = true, subscribeError = null)) }
        viewModelScope.launch {
            val outcome = subscribeUseCase.youTube(step.resolution, step.variants, groupIds)
            mutableState.update { state ->
                if (state.operationGeneration != generation) return@update state
                val current = state.step as? AddSheetStep.Channel ?: return@update state
                when (outcome) {
                    is Outcome.Success -> {
                        val title = step.resolution.title ?: step.resolution.channelId
                        state.copy(done = SubscribedPodcast(outcome.value, title, generation))
                    }

                    is Outcome.Failure -> {
                        val duplicate = (outcome.error as? SubscribeError.AlreadySubscribed)?.podcastId
                        val message = if (duplicate == null) SubscribeErrorText.describe(outcome.error) else null
                        val failed =
                            current.copy(
                                subscribing = false,
                                alreadySubscribedId = duplicate,
                                subscribeError = message,
                            )
                        state.copy(step = failed)
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
                if (resolution is AddResolution.YouTube) {
                    resolveChannel(generation, resolution.ref)
                } else {
                    apply(generation, resolution)
                }
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
                        is AddResolution.YouTube -> {
                            error("Channel resolution is handled asynchronously")
                        }

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
                                searchQuery = (resolution.error as? AddPodcastError.NotAUrl)?.query,
                            )
                        }
                    },
            )
        }
    }
}
