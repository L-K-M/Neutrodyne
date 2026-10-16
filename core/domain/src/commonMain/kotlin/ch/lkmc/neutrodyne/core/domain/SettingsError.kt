// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

/**
 * Why a [SettingsRepository] or [ScopeSettingsRepository] write failed (01/05). Both
 * errors leave the previously stored value untouched.
 */
sealed interface SettingsError {
    /** The settings store write failed (DataStore I/O or SQLite); logged at WARN by the repository. */
    data object WriteFailed : SettingsError

    /** The value was rejected by its key or scoped-field validator. */
    data class OutOfRange(
        val key: String,
    ) : SettingsError
}
