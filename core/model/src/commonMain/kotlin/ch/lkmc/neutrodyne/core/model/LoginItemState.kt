// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/**
 * The OS start-at-login entry's state (11 Start at login). Lives in `:core:model` — an
 * implementation note there records why — because the shared Settings › Desktop row renders it
 * through `DesktopIntegration`, while `:desktop:system`'s registrars are JVM-only.
 */
public enum class LoginItemState {
    /** The OS entry exists and is enabled. */
    REGISTERED,

    /** No OS entry; the OS mechanism can register one. */
    NOT_REGISTERED,

    /** Registration is impossible here — no `jpackage.app-path` (dev run) or no mechanism yet. */
    UNSUPPORTED,

    /** Registered but held for the user's OS-side approval (macOS SMAppService). */
    REQUIRES_APPROVAL,

    /** The bundle runs from /AppTranslocation/ and must be moved to Applications first. */
    TRANSLOCATED,

    /** The entry exists but the OS disabled it (Windows `StartupApproved` byte `03`). */
    DISABLED_BY_OS,
}
