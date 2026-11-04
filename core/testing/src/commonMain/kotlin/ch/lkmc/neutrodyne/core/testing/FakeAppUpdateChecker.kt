// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.update.AppUpdateChecker
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckState
import ch.lkmc.neutrodyne.core.model.update.UpdateDisabledReason
import kotlinx.coroutines.flow.MutableStateFlow

/** Controllable card/badge fake; no clock, scheduling or network simulation. */
class FakeAppUpdateChecker(
    initial: UpdateCheckState = UpdateCheckState.Idle(null),
) : AppUpdateChecker {
    override val state = MutableStateFlow(initial)
    override val writeError = MutableStateFlow<SettingsError?>(null)
    var nextCheckState: UpdateCheckState? = null
    var failNextWrite: SettingsError? = null
    private val loggedCalls = mutableListOf<String>()
    val calls: List<String> get() = loggedCalls.toList()

    override suspend fun checkNow(): UpdateCheckState {
        loggedCalls += "checkNow"
        if (state.value == UpdateCheckState.Disabled(UpdateDisabledReason.DEV_BUILD)) return state.value

        nextCheckState?.let { state.value = it }
        nextCheckState = null
        return state.value
    }

    override fun skip(versionCode: Long) {
        loggedCalls += "skip($versionCode)"
        val available = state.value as? UpdateCheckState.Available ?: return
        if (available.info.versionCode != versionCode) return

        val failure = failNextWrite
        failNextWrite = null
        writeError.value = failure
        if (failure != null) return

        state.value = UpdateCheckState.Idle(available.lastCheckAtMs)
    }
}
