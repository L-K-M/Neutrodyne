// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.runInitializers
import ch.lkmc.neutrodyne.desktop.di.DesktopAppGraph
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Desktop entry point. M1a follows 11's start-up order as far as the shell exists: `AppDirs` →
 * directories → graph → the initializer bands (the database open is band 100). `SingleInstanceLock`
 * and the window with its start-up gate arrive in M0b (11 Desktop shell); until then `main` runs
 * the bands and exits.
 */
fun main() {
    val dirs = AppDirs.current()
    dirs.ensureCreated()
    val graph = createGraphFactory<DesktopAppGraph.Factory>().create(dirs)
    runBlocking(Dispatchers.Default) { runInitializers(graph.initializers) }
    println("Neutrodyne desktop")
}
