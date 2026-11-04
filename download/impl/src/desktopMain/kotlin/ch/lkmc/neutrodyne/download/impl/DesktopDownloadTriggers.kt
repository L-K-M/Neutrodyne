// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.JobLanePoker
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.datastore.DeviceSettingsStore
import ch.lkmc.neutrodyne.core.domain.AutoDownloadPlanner
import ch.lkmc.neutrodyne.core.domain.CleanupReason
import ch.lkmc.neutrodyne.core.domain.EffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.domain.IngestionEvents
import ch.lkmc.neutrodyne.core.domain.RefreshController
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.EffectiveAutoDownload
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Application lifetime only; daily/startup passes rederive anything missed while quit. */
@ContributesIntoSet(AppScope::class)
@Inject
internal class DesktopDownloadObservers(
    private val components: Lazy<DownloadComponents>,
    private val startup: DesktopDownloadStartup,
    private val resolver: Lazy<EffectiveSettingsResolver>,
    private val ingestion: IngestionEvents,
    private val refresh: Lazy<RefreshController>,
    private val device: DeviceSettingsStore,
    private val db: Lazy<NeutrodyneDatabase>,
    private val poker: () -> JobLanePoker,
    private val capabilities: Lazy<YouTubeCapabilitiesSource>,
    @param:ApplicationScope private val scope: CoroutineScope,
) : AppInitializer {
    override val order = 310

    override suspend fun run() {
        scope.launch {
            DesktopDownloadTriggers(
                resolver.value,
                ingestion,
                refresh.value,
                object : AutoDownloadPlanner {
                    override suspend fun plan(podcastIds: Collection<Long>) {
                        startup.ensureStarted()
                        components.value.planner.plan(podcastIds)
                    }
                },
                { components.value.cleanup.run(CleanupReason.POLICY_CHANGED) },
            ).observe()
        }
        scope.launch {
            // A transient disk/DB failure must not permanently lose the played observer.
            while (isActive) {
                guarded("Played cleanup observer") { components.value.cleanup.observePlayed() }
                delay(OBSERVER_RETRY_MS)
            }
        }
        scope.launch {
            device.observe(DownloadSettingKeys.MOVE_TARGET).collect { target ->
                if (target.isNotEmpty()) poker().poke(DesktopMoveLane.NAME)
            }
        }
        scope.launch {
            db.value.playSessionDao().observeCurrentEpisodeId().distinctUntilChanged().collect {
                if (device.get(DownloadSettingKeys.MOVE_TARGET).isNotEmpty()) poker().poke(DesktopMoveLane.NAME)
            }
        }
        scope.launch {
            capabilities.value.capabilities
                .map { it.downloads to it.externalReason }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    components.value.claimMutex.withLock { components.value.youtubeRules.syncLocked() }
                    for (lane in DownloadLane.entries) poker().poke(DesktopDownloadLane.name(lane))
                }
        }
    }

    private companion object {
        const val OBSERVER_RETRY_MS = 60_000L
    }
}

/** Batch ingestion with a fixed window: a continuous refresh must not starve planning. */
@OptIn(FlowPreview::class)
internal class DesktopDownloadTriggers(
    private val resolver: EffectiveSettingsResolver,
    private val ingestion: IngestionEvents,
    private val refresh: RefreshController,
    private val planner: AutoDownloadPlanner,
    private val policyCleanup: suspend () -> Unit,
) {
    suspend fun observe(): Unit =
        coroutineScope {
            launch {
                val pending = mutableSetOf<Long>()
                val mutex = Mutex()
                val signal = Channel<Unit>(Channel.CONFLATED)
                launch {
                    ingestion.newEpisodes.collect { event ->
                        if (event.initialFetch) return@collect
                        mutex.withLock { pending += event.podcastId }
                        signal.trySend(Unit)
                    }
                }
                for (ignored in signal) {
                    delay(INGEST_BATCH_MS)
                    val ids = mutex.withLock { pending.toSet().also { pending.clear() } }
                    if (ids.isNotEmpty()) guarded("Ingestion planner") { planner.plan(ids) }
                }
            }
            launch {
                refresh.observeStatus().map { it.running }.distinctUntilChanged().drop(1).collect { running ->
                    if (!running) guarded("Refresh planner") { planAll() }
                }
            }
            launch {
                var previous = emptyMap<Long, EffectiveAutoDownload>()
                resolver.observeAutoDownload().distinctUntilChanged().debounce(POLICY_DEBOUNCE_MS).collect { policies ->
                    val changed = (previous.keys + policies.keys).filter { previous[it] != policies[it] }
                    previous = policies
                    guarded("Policy planner") {
                        planner.plan(changed)
                        policyCleanup()
                    }
                }
            }
        }

    private suspend fun planAll() = planner.plan(resolver.observeAutoDownload().first().keys)

    private companion object {
        const val INGEST_BATCH_MS = 5_000L
        const val POLICY_DEBOUNCE_MS = 2_000L
    }
}

private suspend fun guarded(
    label: String,
    block: suspend () -> Unit,
) {
    suspendRunCatching { block() }.onFailure { Log.e("Downloads", it) { "$label failed" } }
}
