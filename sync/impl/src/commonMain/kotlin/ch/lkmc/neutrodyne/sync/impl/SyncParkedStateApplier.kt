// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider

/**
 * 10 Parked state and stubs, the release side: after an ingest inserted or re-keyed episodes, the
 * podcast's parked rows are matched with 02's restore ladder and applied through [SyncApplier]'s
 * standard merge and collection writes. Rows that still cannot resolve stay parked — an honest
 * retry on the next ingest, dependency arrival or restart, never a poll loop.
 *
 * Both dependencies stay behind [Provider]s (01 DI rule 7, cheap construction): the hook is bound
 * into `FeedRefresher` and `SyncApplier`, and resolving those must never block on the database
 * open — the database is reached only when a retry actually runs, after the band-100 open.
 */
class SyncParkedStateApplier(
    private val db: Provider<NeutrodyneDatabase>,
    private val applier: Provider<SyncApplier>,
) : SyncIngestHook {
    /** 03 Diff algorithm step 11: the hook `FeedRefresher` calls after its ingest commit. */
    override suspend fun afterIngest(podcastId: Long) {
        val syncId = db().podcastDao().byId(podcastId)?.syncId ?: return
        retryPodcast(syncId)
    }

    /** One podcast's parked rows — the ingest hook's and the diagnostics' entry. */
    suspend fun retryPodcast(podcastSyncId: String): SyncApplyResult =
        applier().applyParkedRecords(db().syncParkedDao().forPodcast(podcastSyncId))

    /**
     * The restart pass: a crash after an ingest commit but before `afterIngest` must not strand
     * records behind the cursor that already advanced past them. Runs once per start; empty
     * tables make it a no-op.
     */
    suspend fun retryAll(): SyncApplyResult = applier().applyParkedRecords(db().syncParkedDao().all())
}

/**
 * The parked-state start-up recovery (01 Application start-up, band 191 — after the database open
 * at 100 and the settings drain at 160, before the scheduling arms at 200). Lazy dependencies:
 * resolving the applier reaches the database.
 */
@ContributesIntoSet(AppScope::class)
class SyncParkedRecoveryInitializer
    @Inject
    constructor(
        private val parked: Provider<SyncParkedStateApplier>,
    ) : AppInitializer {
        override val order: Int = 191

        override suspend fun run() {
            parked().retryAll()
        }
    }
