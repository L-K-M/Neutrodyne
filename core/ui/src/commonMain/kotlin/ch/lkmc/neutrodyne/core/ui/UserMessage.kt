// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A snackbar-bound message (01 ViewModels and UI state): [id] de-duplicates and acknowledges it,
 * [text] is the body and [action] the optional action label.
 */
public data class UserMessage(
    val id: Long,
    val text: UiText,
    val action: UiText? = null,
)

/**
 * A screen's pending-snackbar list (01: one-shot events are state with acknowledgement, never a
 * lossy `Channel`/`SharedFlow`). ViewModels [post]; the screen merges [flow] into its UiState,
 * shows each message and calls [shown] once the snackbar was dismissed. Ids are unique per
 * instance; callers run on the main thread (viewModelScope, composition).
 */
public class UserMessages {
    private val pending = MutableStateFlow<ImmutableList<UserMessage>>(persistentListOf())
    private var nextId = 0L

    /** The outstanding messages; merge into the UiState or collect beside it. */
    public val flow: StateFlow<ImmutableList<UserMessage>> = pending.asStateFlow()

    public fun post(
        text: UiText,
        action: UiText? = null,
    ) {
        pending.update { (it + UserMessage(nextId++, text, action)).toImmutableList() }
    }

    /** Acknowledgement: the screen calls it after [id]'s snackbar was dismissed. */
    public fun shown(id: Long) {
        pending.update { list -> list.filterNot { it.id == id }.toImmutableList() }
    }
}
