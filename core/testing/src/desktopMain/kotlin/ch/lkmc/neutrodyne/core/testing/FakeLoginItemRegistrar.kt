// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.LoginItemException
import ch.lkmc.neutrodyne.core.common.LoginItemRegistrar
import ch.lkmc.neutrodyne.core.model.LoginItemState

/**
 * The `LoginItemRegistrar` fake every new domain/API port ships (AGENTS): status is the
 * caller-set [state] — register/unregister only record calls and apply the state an OS
 * registrar would reach.
 */
class FakeLoginItemRegistrar(
    initial: LoginItemState = LoginItemState.NOT_REGISTERED,
) : LoginItemRegistrar {
    var state = initial
        private set

    var registerCalls = 0
        private set

    var unregisterCalls = 0
        private set

    /** Set to make [register] throw, like an OS refusing the write. */
    var registerError: LoginItemException? = null

    /**
     * Set to make [status] throw, like a registry/IO/JNA error reading the OS entry. The
     * last successful [state] stays readable for the failure-path assertions.
     */
    var statusError: RuntimeException? = null

    override fun status(): LoginItemState {
        statusError?.let { throw it }
        return state
    }

    override fun register() {
        registerCalls++
        registerError?.let { throw it }
        state = LoginItemState.REGISTERED
    }

    override fun unregister() {
        unregisterCalls++
        if (state != LoginItemState.UNSUPPORTED) {
            state = LoginItemState.NOT_REGISTERED
        }
    }
}
