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

    /**
     * The M1a sync-hook stub (03 Diff algorithm step 11): sync is not configured, so nothing is
     * ever parked. 10's `SyncParkedStateApplier` replaces this binding at MS2.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun syncIngestHook(): SyncIngestHook = SyncIngestHook.None
}
