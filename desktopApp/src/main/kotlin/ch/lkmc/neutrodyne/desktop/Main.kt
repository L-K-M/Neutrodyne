// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop

import ch.lkmc.neutrodyne.desktop.smoke.AotTrainingMode
import ch.lkmc.neutrodyne.desktop.smoke.SmokeMode
import kotlin.system.exitProcess

/**
 * The desktop entry point (11 Start-up sequence). The training and smoke modes run first —
 * they never touch the user's directories, so they must not pass the single-instance lock
 * either. Everything else is [DesktopShell].
 */
fun main(args: Array<String>) {
    if (AotTrainingMode.isEnabled(System.getProperty(AotTrainingMode.SYSTEM_PROPERTY))) {
        exitProcess(AotTrainingMode().run())
    }
    if (SmokeMode.isEnabled(System.getProperty(SmokeMode.SYSTEM_PROPERTY))) {
        exitProcess(SmokeMode().run())
    }
    exitProcess(DesktopShell.start(args))
}
