// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.model.NewEpisodes
import kotlinx.coroutines.flow.SharedFlow

/**
 * The ingest event contract (03 Events, canonical here): `IngestionEventBus` (`:core:data`,
 * singleton) is bound as this interface; consumers re-derive work they must not miss from the
 * database — delivery is in-process and best effort.
 */
interface IngestionEvents {
    val newEpisodes: SharedFlow<NewEpisodes>
}

/**
 * The MS2 sync hook the engine calls after an ingest that inserted or re-keyed at least one row
 * (03 Diff algorithm step 11): 10's `SyncParkedStateApplier` implements it to apply parked played
 * state, positions, favourites and Up next. The M1a binding returns at once while no sync server
 * is configured.
 */
fun interface SyncIngestHook {
    suspend fun afterIngest(podcastId: Long)

    companion object {
        /** The M1a binding: sync is not configured, so nothing is ever parked. */
        val None = SyncIngestHook { }
    }
}
