// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.DesktopIntegration
import ch.lkmc.neutrodyne.core.domain.StartAtLoginError
import ch.lkmc.neutrodyne.core.model.LoginItemState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The `DesktopIntegration` fake (AGENTS' every-port rule). [loginItem] is the mutable state a
 * test seeds; [setStartAtLogin] applies the OS truth a real registrar would reach — enable
 * registers, disable removes — or replays [nextError].
 */
class FakeDesktopIntegration(
    initial: LoginItemState = LoginItemState.NOT_REGISTERED,
) : DesktopIntegration {
    private val state = MutableStateFlow(initial)
    override val loginItem: StateFlow<LoginItemState> = state

    var nextError: StartAtLoginError? = null
    var lastEnabled: Boolean? = null
        private set

    /** Emulates an external change (the user editing the OS entry) in a test. */
    fun emit(state: LoginItemState) {
        this.state.value = state
    }

    override suspend fun setStartAtLogin(enabled: Boolean): Outcome<Unit, StartAtLoginError> {
        lastEnabled = enabled
        nextError?.let { return Outcome.Failure(it) }
        state.value = if (enabled) LoginItemState.REGISTERED else LoginItemState.NOT_REGISTERED
        return Outcome.Success(Unit)
    }
}
