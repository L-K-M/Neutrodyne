// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop

import ch.lkmc.neutrodyne.desktop.smoke.SmokeMode
import kotlin.system.exitProcess

/**
 * The desktop entry point (11 Start-up sequence). Smoke mode runs first — it never touches the
 * user's directories, so it must not pass the single-instance lock either. Everything else is
 * [DesktopShell].
 */
fun main(args: Array<String>) {
    if (SmokeMode.isEnabled(System.getProperty(SmokeMode.SYSTEM_PROPERTY))) {
        exitProcess(SmokeMode().run())
    }
    exitProcess(DesktopShell.start(args))
}
