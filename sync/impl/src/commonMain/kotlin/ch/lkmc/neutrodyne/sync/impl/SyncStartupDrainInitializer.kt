// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider

/**
 * 10 Durable apply effects / Settings capture: the at-each-start drain, on both platforms
 * (01 Application start-up band 160 — data, after the database open at 100, before the
 * scheduling band arms any push or reconciliation). Local only: Room markers plus DataStore
 * writes, no network call, and nothing runs while unlinked.
 */
@ContributesIntoSet(AppScope::class)
class SyncStartupDrainInitializer
    @Inject
    constructor(
        // Lazy (01 DI rule 7): the drainer reaches the database, which band 100 opens first.
        private val effects: Provider<SyncApplyEffects>,
    ) : AppInitializer {
        override val order: Int = 160

        override suspend fun run() {
            effects().drainAtStart()
        }
    }
