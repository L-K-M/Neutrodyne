// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.AutoDownloadPlanner
import ch.lkmc.neutrodyne.core.domain.CleanupPlanner
import ch.lkmc.neutrodyne.core.domain.CleanupReason
import ch.lkmc.neutrodyne.core.domain.EffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.domain.IngestionEvents
import ch.lkmc.neutrodyne.core.domain.RefreshController
import ch.lkmc.neutrodyne.core.model.EffectiveAutoDownload
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Process-local events are hints. Startup, refresh-idle and daily passes recover missed events. */
@OptIn(FlowPreview::class)
internal class AndroidDownloadTriggers
    @dev.zacsweers.metro.Inject
    constructor(
        private val planner: AutoDownloadPlanner,
        private val cleanup: CleanupPlanner,
        private val resolver: EffectiveSettingsResolver,
        private val ingestion: IngestionEvents,
        private val refresh: RefreshController,
    ) {
        suspend fun observe(): Nothing =
            coroutineScope {
                launch { observeIngestion() }
                launch {
                    var wasRunning = false
                    refresh.observeStatus().map { it.running }.distinctUntilChanged().collect { running ->
                        if (wasRunning && !running) {
                            safely {
                                planner.plan(
                                    resolver
                                        .observeAutoDownload()
                                        .first()
                                        .filterValues { it.enabled }
                                        .keys,
                                )
                            }
                        }
                        wasRunning = running
                    }
                }
                launch {
                    var previous = emptyMap<Long, EffectiveAutoDownload>()
                    resolver.observeAutoDownload().debounce(POLICY_DEBOUNCE_MS).collect { policies ->
                        val changed = (previous.keys + policies.keys).filter { previous[it] != policies[it] }
                        // Even an empty changed set must perform the full watermark pass.
                        val succeeded =
                            safely {
                                planner.plan(changed)
                                cleanup.run(CleanupReason.POLICY_CHANGED)
                            }
                        if (succeeded) previous = policies
                    }
                }
                kotlinx.coroutines.awaitCancellation()
            }

        private suspend fun observeIngestion() =
            coroutineScope {
                val events = ingestion.newEpisodes.filter { !it.initialFetch }.produceIn(this)
                try {
                    while (true) {
                        val ids = mutableSetOf(events.receive().podcastId)
                        // Fixed window from the first event, rather than a debounce that a long refresh
                        // could postpone indefinitely. Events arriving during planning stay buffered.
                        withTimeoutOrNull(INGEST_BATCH_MS) {
                            while (true) ids += events.receive().podcastId
                        }
                        safely { planner.plan(ids) }
                    }
                } finally {
                    events.cancel()
                }
            }

        private suspend fun safely(block: suspend () -> Unit): Boolean =
            suspendRunCatching { block() }
                .onFailure { Log.e("Downloads", it) { "Automatic download trigger failed" } }
                .isSuccess

        private companion object {
            const val INGEST_BATCH_MS = 5_000L
            const val POLICY_DEBOUNCE_MS = 2_000L
        }
    }
