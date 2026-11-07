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
 * This slice carries start at login (MD2 part 2) and the links and applications-menu rows
 * of MD2's links slice; the access-bridge status joins with MD4 (08's frozen sketch).
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

    /**
     * Whether Neutrodyne handles `feed:`, `podcast:`, `pcast:` and `itpc:` links (11 Links
     * and files from the OS). `null` = not applicable: install kinds that register the
     * schemes at install time (DMG, DEB and RPM) hide the row.
     */
    val linkHandlers: StateFlow<Boolean?>

    /**
     * The "Open podcast links with Neutrodyne" row's "Set up": takes the podcast schemes
     * over on request. `false` when the OS write failed; [linkHandlers] re-reads the OS
     * state either way.
     */
    suspend fun registerLinkHandlers(): Boolean

    /** "Add to applications menu" exists only on Linux tar.gz installs (11's tar.gz row). */
    val canAddToApplicationsMenu: Boolean

    /**
     * Writes the per-user desktop entry and icons of 11's tar.gz row. `false` when the
     * write failed; re-running after the archive moved refreshes the entry.
     */
    suspend fun addToApplicationsMenu(): Boolean
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
