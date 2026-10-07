// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.di

import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.DependencyGraph

/**
 * The desktop shell's Metro graph (01 "Dependency injection", D82; 11 Desktop shell). Minimal M0a
 * skeleton after spike S8: `DesktopCoreBindings`, the `AppDirs`/`BuildInfo` factory inputs and the
 * job runner arrive with M0b step 29.
 */
@DependencyGraph(AppScope::class)
interface DesktopAppGraph
