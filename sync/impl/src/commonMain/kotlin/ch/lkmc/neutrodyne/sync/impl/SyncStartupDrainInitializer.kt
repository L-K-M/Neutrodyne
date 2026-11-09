// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.domain.SyncServerAddress
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
        private val flags: SyncFlags,
        // Lazy (01 DI rule 7): the drainer reaches the database, which band 100 opens first.
        private val address: Provider<SyncServerAddress>,
        private val effects: Provider<SyncApplyEffects>,
        private val capture: Provider<SettingsCapture>,
    ) : AppInitializer {
        override val order: Int = 160

        override suspend fun run() {
            // The volatile share gate primes before the drain and the band-191 parked recovery
            // read it — the persisted switch, not the restart default, is what they observe.
            // Only configured devices read the saved switches. The existing drain retains
            // its linked-state guard; band 100 has already opened the database here.
            val addr = address()
            if (addr.serverUrl() != null) flags.refreshFromStore(addr)
            effects().drainAtStart(capture())
        }
    }
