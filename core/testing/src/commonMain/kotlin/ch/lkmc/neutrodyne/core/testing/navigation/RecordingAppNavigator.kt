// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing.navigation

import androidx.navigation3.runtime.NavKey
import ch.lkmc.neutrodyne.core.navigation.AppNavigator
import ch.lkmc.neutrodyne.core.navigation.TopLevelKey

/**
 * Records every `AppNavigator` call in order (09 Shared helpers). Tests bind it under
 * `LocalAppNavigator` and assert on [calls] — e.g. that the gear pushed `SettingsHomeKey`.
 */
public class RecordingAppNavigator : AppNavigator {
    public sealed interface Call {
        public data class Push(val key: NavKey) : Call

        public data class SelectTab(val key: TopLevelKey) : Call

        public data object Pop : Call

        public data class ResetTab(val key: TopLevelKey) : Call

        public data class Open(val tab: TopLevelKey, val stack: List<NavKey>) : Call

        public data class PushDetail(val key: NavKey) : Call
    }

    public val calls: MutableList<Call> = mutableListOf()

    /** Pops once per [Call.Pop] to mirror the real navigator's return value; tests may preset it. */
    public var popResult: Boolean = true

    override fun push(key: NavKey) {
        calls += Call.Push(key)
    }

    override fun selectTab(key: TopLevelKey) {
        calls += Call.SelectTab(key)
    }

    override fun pop(): Boolean {
        calls += Call.Pop
        return popResult
    }

    override fun resetTab(key: TopLevelKey) {
        calls += Call.ResetTab(key)
    }

    override fun open(tab: TopLevelKey, stack: List<NavKey>) {
        calls += Call.Open(tab, stack)
    }

    override fun pushDetail(key: NavKey) {
        calls += Call.PushDetail(key)
    }
}
