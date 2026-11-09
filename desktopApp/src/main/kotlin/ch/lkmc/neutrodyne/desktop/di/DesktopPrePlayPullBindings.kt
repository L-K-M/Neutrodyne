// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.desktop.di

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.data.PrePlaySyncBindings
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.domain.PrePlaySync
import ch.lkmc.neutrodyne.sync.impl.PrePlaySyncImpl
import ch.lkmc.neutrodyne.sync.impl.SyncEngine
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope

/**
 * The production pull-before-play binding (10 pull before play). Replaces
 * [PrePlaySyncBindings], the standalone no-op default. Both resolution paths stay lazy: the
 * database resolver suspends on the open gate instead of blocking a caller thread, and the
 * engine resolves only inside a spawned round — never at graph construction or at an
 * unconfigured first frame.
 */
@BindingContainer
@ContributesTo(AppScope::class, replaces = [PrePlaySyncBindings::class])
object DesktopPrePlayPullBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun prePlaySync(
        opener: DatabaseOpener,
        engine: Provider<SyncEngine>,
        network: NetworkMonitor,
        clock: Clock,
        @ApplicationScope scope: CoroutineScope,
    ): PrePlaySync =
        PrePlaySyncImpl(
            openDb = {
                opener.awaitOpen()
                opener.requireDatabase()
            },
            round = { reason -> engine().round(reason) },
            network = network,
            clock = clock,
            scope = scope,
        )
}
