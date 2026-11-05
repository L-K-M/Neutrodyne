// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import ch.lkmc.neutrodyne.core.model.LoginItemState

/**
 * The OS start-at-login entry (11 Start at login). A `:core:common` `desktopMain` port like
 * `PowerMonitor` and `DesktopNotifier`: implemented per OS in `:desktop:system`
 * (`WindowsRunKeyRegistrar`, `XdgAutostartRegistrar`, the deferred `MacLoginItemRegistrar`) and
 * bound in `DesktopAppGraph`; `DesktopIntegrationImpl` wraps it for Settings › Desktop.
 *
 * The OS entry is the truth — [status] reads it back, so a registration the user removed or
 * disabled in the OS shows accordingly. All three calls are quick local reads/writes (one
 * registry value, one small file); they stay synchronous, called off the UI path.
 */
interface LoginItemRegistrar {
    /** The entry's state as the OS reports it right now. */
    fun status(): LoginItemState

    /**
     * Writes the entry (the launcher plus `--background`).
     *
     * @throws LoginItemException when there is no launcher path or the OS refused the write.
     */
    fun register()

    /** Removes the entry. A missing entry is a no-op. */
    fun unregister()
}

/** A refused or failed [LoginItemRegistrar] operation; the reason is safe to log. */
class LoginItemException(
    message: String,
) : Exception(message)
