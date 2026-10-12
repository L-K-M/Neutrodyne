// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import ch.lkmc.neutrodyne.core.database.DatabaseOpenException
import ch.lkmc.neutrodyne.core.database.DatabaseOpenState
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.startup_recovered
import ch.lkmc.neutrodyne.core.ui.root.StartupFailure
import ch.lkmc.neutrodyne.core.ui.root.StartupGateState

/**
 * The desktop shell's `DatabaseOpener → StartupGateState` mapping (01 Splash and start-up gate):
 * the same mapping `:app`'s `MainActivity` applies, kept per shell because `:core:ui` cannot see
 * `:core:database` and each shell words its own actions.
 */
internal fun DatabaseOpenState.toGateState(): StartupGateState =
    when (this) {
        DatabaseOpenState.Pending -> {
            StartupGateState.Pending
        }

        is DatabaseOpenState.Opened -> {
            if (result.recovered != null) {
                StartupGateState.Recovered(UiText.Res(Res.string.startup_recovered))
            } else {
                StartupGateState.Ready
            }
        }

        is DatabaseOpenState.Failed -> {
            StartupGateState.Failed(
                when (exception.reason) {
                    DatabaseOpenException.Reason.DISK_FULL -> StartupFailure.DISK_FULL
                    DatabaseOpenException.Reason.IO -> StartupFailure.IO
                    DatabaseOpenException.Reason.UNKNOWN -> StartupFailure.UNKNOWN
                },
            )
        }
    }
