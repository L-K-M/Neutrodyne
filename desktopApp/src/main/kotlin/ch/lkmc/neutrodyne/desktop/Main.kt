// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop

import ch.lkmc.neutrodyne.desktop.di.DesktopAppGraph
import dev.zacsweers.metro.createGraph

/**
 * Desktop entry point. M0a builds the graph only; the shell (single instance, window, tray, smoke mode) arrives
 * in M0b (11 Desktop shell).
 */
fun main() {
    createGraph<DesktopAppGraph>()
    println("Neutrodyne desktop")
}
