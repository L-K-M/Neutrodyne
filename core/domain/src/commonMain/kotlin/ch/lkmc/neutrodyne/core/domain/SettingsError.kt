// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

/**
 * Why a [SettingsRepository] write failed (01 DataStore files and typed setting keys). Both
 * errors leave the previously stored value untouched.
 */
sealed interface SettingsError {
    /** The DataStore write threw (disk full, I/O error); logged at WARN by the repository. */
    data object WriteFailed : SettingsError

    /** The value was rejected by the key's own validator (for example a `Choice` not in its values). */
    data class OutOfRange(
        val key: String,
    ) : SettingsError
}
