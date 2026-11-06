// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds

/**
 * Declares the `Set<LevelMutableLogSink>` multibinding: the shells contribute their mutable
 * sinks — the diagnostics ring and, on the desktop, the pre-graph rolling file sink — through
 * `@Provides @IntoSet` in their own graphs, and `VerboseLogInitializer` lowers every member
 * while "Detailed log for 24 hours" is armed. `allowEmpty` keeps journey graphs compiling
 * where no shell contributes a sink.
 */
@ContributesTo(AppScope::class)
@BindingContainer
public interface LevelMutableLogSinkBindings {
    @Multibinds(allowEmpty = true)
    public fun levelMutableLogSinks(): Set<LevelMutableLogSink>
}
