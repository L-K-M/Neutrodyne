// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.sync.api.LinkFlow
import ch.lkmc.neutrodyne.sync.api.LinkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A scripted [LinkFlow]; [state] is settable so tests can drive the UI's link steps. */
class FakeLinkFlow(
    state: LinkState = LinkState.Address,
) : LinkFlow {
    private val _state = MutableStateFlow(state)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    var cancelCount: Int = 0
        private set

    fun setState(value: LinkState) {
        _state.value = value
    }

    override fun cancel() {
        cancelCount++
    }
}
