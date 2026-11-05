// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.LoginItemState
import kotlinx.coroutines.flow.StateFlow

/**
 * Desktop-only services Settings › Desktop reaches through (08 Settings screens, 11 Desktop
 * settings): bound only in `DesktopAppGraph`, so `:feature:settings` takes it as a nullable
 * parameter defaulting to `null` and shows the page without it elsewhere.
 *
 * This slice carries start at login; link-handler and applications-menu registration and the
 * access-bridge status join it in their own milestone slices (08's frozen sketch).
 */
interface DesktopIntegration {
    /**
     * The OS login item's state read back from the registrar — the OS entry is the truth (11
     * Start at login): an entry the user removed or disabled in the OS reports accordingly.
     */
    val loginItem: StateFlow<LoginItemState>

    /**
     * Applies the "Start at login" switch: writes or removes the OS entry, then records the
     * user's intent in `desktop.start_at_login`. [loginItem] re-reads the OS state afterwards,
     * so a refusal leaves the switch showing the true state.
     */
    suspend fun setStartAtLogin(enabled: Boolean): Outcome<Unit, StartAtLoginError>
}

/** Why a "Start at login" toggle did not apply. */
sealed interface StartAtLoginError {
    /**
     * No registration mechanism here — a dev run without `jpackage.app-path`, or an OS whose
     * registrar is still pending (macOS, 11's dated note).
     */
    data object Unsupported : StartAtLoginError

    /** The OS refused or the write failed; the message is safe to log. */
    data class Failed(
        val message: String,
    ) : StartAtLoginError
}
