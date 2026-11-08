// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.refresh

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlin.random.Random

/** The refresh engine's remaining singletons (03 Refresh scheduling). */
@BindingContainer
@ContributesTo(AppScope::class)
object RefreshBindings {
    /** The backoff jitter's entropy source; tests inject `Random(seed)` directly. */
    @Provides
    fun random(): Random = Random
}

/**
 * The M1a sync-hook default (03 Diff algorithm step 11): without a sync server nothing is ever
 * parked, so `:core:data`'s standalone graphs bind [SyncIngestHook.None]. The app composition
 * roots replace this container with 10's `SyncParkedStateApplier` binding at MS2.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object SyncIngestHookBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun syncIngestHook(): SyncIngestHook = SyncIngestHook.None
}
