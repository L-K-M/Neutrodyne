// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.di

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.data.refresh.SyncIngestHookBindings
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.sync.impl.SyncApplier
import ch.lkmc.neutrodyne.sync.impl.SyncParkedStateApplier
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * The production sync-hook binding (10 Parked state and stubs): `FeedRefresher`'s post-ingest
 * callback releases parked records through 10's `SyncParkedStateApplier`. Replaces
 * [SyncIngestHookBindings], the M1a no-op default that `:core:data`'s standalone graphs keep.
 */
@BindingContainer
@ContributesTo(AppScope::class, replaces = [SyncIngestHookBindings::class])
object ParkedSyncHookBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun syncParkedStateApplier(
        // 01 DI rule 7: the hook binds into FeedRefresher and SyncApplier, so neither its
        // construction nor their resolution may block on the database open.
        db: Provider<NeutrodyneDatabase>,
        applier: Provider<SyncApplier>,
    ): SyncParkedStateApplier = SyncParkedStateApplier(db, applier)

    @Provides
    fun syncIngestHook(applier: SyncParkedStateApplier): SyncIngestHook = applier
}
