// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.CrashContext
import ch.lkmc.neutrodyne.core.common.CrashKey
import ch.lkmc.neutrodyne.core.common.CrashReporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mutable [CrashReporter] for tests (09 `:core:testing` inventory): records every
 * [reportNonFatal] call; [setAvailable] flips [CrashReporter.isAvailable] synchronously.
 */
class FakeCrashReporter(
    initialAvailable: Boolean = false,
) : CrashReporter {
    private val _isAvailable = MutableStateFlow(initialAvailable)

    override val isAvailable: Boolean
        get() = _isAvailable.value

    /** The recorded calls as `where` names, in order. */
    val calls: List<String>
        get() = _calls.value

    private val _calls = MutableStateFlow(listOf<String>())

    /** The reported throwables, in order. */
    val reported: List<Throwable>
        get() = _reported.value

    private val _reported = MutableStateFlow(listOf<Throwable>())

    fun setAvailable(available: Boolean) {
        _isAvailable.value = available
    }

    override fun reportNonFatal(t: Throwable, where: String) {
        _calls.value = _calls.value + where
        _reported.value = _reported.value + t
    }
}

/**
 * In-memory [CrashContext] for tests: [put] appends to the observable [values] map (last write
 * wins per key, like the real implementations).
 */
class FakeCrashContext : CrashContext {
    private val _values = MutableStateFlow<Map<CrashKey, String>>(emptyMap())

    /** The context as the next crash report would read it. */
    val values: StateFlow<Map<CrashKey, String>> = _values.asStateFlow()

    override fun put(key: CrashKey, value: String) {
        _values.value = _values.value + (key to value)
    }
}
