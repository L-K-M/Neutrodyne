// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.api

/** A device-local Sync switch could not be saved; its previous value remains in effect. */
sealed interface SyncPreferenceError {
    data object WriteFailed : SyncPreferenceError
}
