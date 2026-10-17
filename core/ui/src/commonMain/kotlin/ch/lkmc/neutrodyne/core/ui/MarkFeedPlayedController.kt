// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlin.time.Instant

public enum class MarkPlayedOption { ALL, WEEK, MONTH, THREE_MONTHS }

/** UI outcome mapped by the feature, keeping :core:ui independent of :core:domain (01 rule 7). */
public sealed interface MarkPlayedOutcome {
    public data object Played : MarkPlayedOutcome

    public data class SelectionChanged(
        val actualCount: Int,
    ) : MarkPlayedOutcome
}

public enum class MarkPlayedPhase { COUNTING, READY, WRITING }

public data class MarkFeedPlayedState(
    val source: FeedSource,
    val title: UiText,
    val option: MarkPlayedOption = MarkPlayedOption.ALL,
    val sortDateBefore: Long? = null,
    val count: Int? = null,
    val phase: MarkPlayedPhase = MarkPlayedPhase.COUNTING,
    val selectionChanged: Boolean = false,
    val error: UiText? = null,
) {
    public val busy: Boolean get() = phase != MarkPlayedPhase.READY
}

/** Shared dialog state owned by the screen ViewModel, so recomposition never moves the cutoff. */
public class MarkFeedPlayedController(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val countUnplayed: suspend (FeedSource, Long?) -> Int,
    private val markFeedPlayed: suspend (FeedSource, Long?, Int) -> MarkPlayedOutcome,
) {
    private val mutableState = MutableStateFlow<MarkFeedPlayedState?>(null)
    public val state: StateFlow<MarkFeedPlayedState?> = mutableState.asStateFlow()
    private var countJob: Job? = null

    public fun open(
        source: FeedSource,
        title: UiText,
    ) {
        if (state.value?.phase == MarkPlayedPhase.WRITING) return
        countJob?.cancel()
        count(MarkFeedPlayedState(source, title))
    }

    public fun select(option: MarkPlayedOption) {
        val current = state.value ?: return
        if (current.phase == MarkPlayedPhase.WRITING) return
        countJob?.cancel()
        // Calendar months in UTC keep the same wall-clock time, including at month ends.
        val now = Instant.fromEpochMilliseconds(clock.now())
        val before =
            when (option) {
                MarkPlayedOption.ALL -> null
                MarkPlayedOption.WEEK -> now.minus(7, DateTimeUnit.DAY, TimeZone.UTC).toEpochMilliseconds()
                MarkPlayedOption.MONTH -> now.minus(1, DateTimeUnit.MONTH, TimeZone.UTC).toEpochMilliseconds()
                MarkPlayedOption.THREE_MONTHS -> now.minus(3, DateTimeUnit.MONTH, TimeZone.UTC).toEpochMilliseconds()
            }
        count(
            current.copy(
                option = option,
                sortDateBefore = before,
                count = null,
                selectionChanged = false,
                error = null,
            ),
        )
    }

    private fun count(selection: MarkFeedPlayedState) {
        mutableState.value = selection.copy(phase = MarkPlayedPhase.COUNTING)
        countJob =
            scope.launch {
                suspendRunCatching {
                    val count = countUnplayed(selection.source, selection.sortDateBefore)
                    // SQLite may finish a cancelled read; never revive an old confirmation.
                    ensureActive()
                    count
                }.onSuccess { mutableState.value = selection.copy(count = it, phase = MarkPlayedPhase.READY) }
                    .onFailure { failed(selection, it) }
            }
    }

    public fun confirm() {
        val selection = state.value ?: return
        val count = selection.count ?: return
        if (selection.busy || count == 0) return
        mutableState.value = selection.copy(phase = MarkPlayedPhase.WRITING, error = null)
        scope.launch {
            suspendRunCatching { markFeedPlayed(selection.source, selection.sortDateBefore, count) }
                .onSuccess { result ->
                    mutableState.value =
                        when (result) {
                            is MarkPlayedOutcome.Played -> {
                                null
                            }

                            is MarkPlayedOutcome.SelectionChanged -> {
                                selection.copy(
                                    count = result.actualCount,
                                    phase = MarkPlayedPhase.READY,
                                    selectionChanged = true,
                                    error = null,
                                )
                            }
                        }
                }.onFailure { failed(selection, it) }
        }
    }

    public fun dismiss() {
        if (state.value?.phase == MarkPlayedPhase.WRITING) return
        countJob?.cancel()
        mutableState.value = null
    }

    private fun failed(
        selection: MarkFeedPlayedState,
        error: Throwable,
    ) {
        Log.w("MarkFeedPlayed", error) { "Bulk selection failed" }
        mutableState.value = selection.copy(phase = MarkPlayedPhase.READY, error = UiText.Res(Res.string.write_failed))
    }
}
