// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.app.Application
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.datastore.DeviceSettingsStore
import ch.lkmc.neutrodyne.core.domain.CleanupReason
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@ContributesIntoSet(AppScope::class)
@Inject
internal class DownloadChannelsInitializer(
    private val notifications: Lazy<DownloadNotifications>,
) : AppInitializer {
    override val order = 10

    override suspend fun run() = notifications.value.ensureChannels()
}

@ContributesIntoSet(AppScope::class)
@Inject
internal class DownloadCleanupInitializer(
    private val queue: Lazy<DownloadWorkQueue>,
) : AppInitializer {
    override val order = 200

    override suspend fun run() = queue.value.cleanup()
}

@ContributesIntoSet(AppScope::class)
@Inject
internal class DownloadReconcileInitializer(
    private val queue: Lazy<DownloadWorkQueue>,
) : AppInitializer {
    override val order = 210

    override suspend fun run() = queue.value.reconcile()
}

@ContributesIntoSet(AppScope::class)
@Inject
internal class DownloadObserversInitializer(
    private val application: Application,
    private val visibility: DownloadAppVisibility,
    private val charging: DownloadChargingMonitor,
    private val triggers: Lazy<AndroidDownloadTriggers>,
    private val components: Lazy<DownloadComponents>,
    private val device: DeviceSettingsStore,
    private val scheduler: Lazy<AndroidDownloadScheduler>,
    private val queue: Lazy<DownloadWorkQueue>,
    private val network: NetworkMonitor,
    private val opener: DatabaseOpener,
    private val db: Lazy<NeutrodyneDatabase>,
    private val notifications: Lazy<DownloadNotifications>,
    @param:DownloadErrorText private val errorText: suspend (DownloadError?) -> String,
    @param:ApplicationScope private val scope: CoroutineScope,
) : AppInitializer {
    override val order = 310

    override suspend fun run() {
        // Band310 only launches; database readiness and collectors never hold startup open.
        scope.launch { charging.observe() }
        scope.launch { charging.charging.collect { schedule() } }
        scope.launch {
            awaitDownloadDatabase(opener)
            triggers.value.observe()
        }
        scope.launch {
            awaitDownloadDatabase(opener)
            components.value.cleanup.observePlayed()
        }
        scope.launch {
            awaitDownloadDatabase(opener)
            device.observe(DownloadSettingKeys.MOVE_TARGET).collect { target ->
                if (target.isNotEmpty()) {
                    suspendRunCatching { queue.value.move(target) }
                        .onFailure { Log.e("Downloads", it) { "Could not schedule download move" } }
                }
            }
        }
        scope.launch(Dispatchers.Main) {
            val lifecycle = ProcessLifecycleOwner.get().lifecycle
            val observer =
                object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        visibility.update(true)
                        schedule()
                    }

                    override fun onStop(owner: LifecycleOwner) {
                        visibility.update(false)
                    }
                }
            lifecycle.addObserver(observer)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { lifecycle.removeObserver(observer) }
            }
        }
        scope.launch {
            network.status.collectLatest { status -> if (status.isConnected) schedule() }
        }
        scope.launch {
            awaitDownloadDatabase(opener)
            db.value.downloadDao().observeEntries().collect { entries ->
                val manual = entries.filter { it.download.lane == DownloadLane.MANUAL }
                notifications.value.queue(manual)
                val failed = manual.filter { it.download.state == DownloadState.FAILED }
                notifications.value.failures(
                    failed.map { it.download.episodeId }.toSet(),
                    failed.firstOrNull()?.episodeTitle.orEmpty(),
                    errorText(failed.firstOrNull()?.download?.lastError),
                )
                notifications.value.storage(
                    manual.count {
                        it.download.state == DownloadState.QUEUED &&
                            it.download.waitReason == WaitReason.STORAGE
                    },
                )
            }
        }
        if (Build.VERSION.SDK_INT >= 30) scope.launch(Dispatchers.Main) { observeVolumes() }
    }

    private fun schedule() {
        scope.launch {
            suspendRunCatching {
                for (lane in DownloadLane.entries) scheduler.value.ensureScheduled(lane)
            }.onFailure { Log.e("Downloads", it) { "Download scheduling failed" } }
        }
    }

    @androidx.annotation.RequiresApi(30)
    private suspend fun observeVolumes() {
        val manager = application.getSystemService(StorageManager::class.java)
        val callback =
            object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) {
                    scope.launch { queue.value.reconcile() }
                }
            }
        manager.registerStorageVolumeCallback(application.mainExecutor, callback)
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { manager.unregisterStorageVolumeCallback(callback) }
        }
    }
}

/** Await the memory snapshot before playback restoration at band 300. */
@ContributesIntoSet(AppScope::class)
@Inject
internal class DownloadIndexInitializer(
    private val components: Lazy<DownloadComponents>,
) : AppInitializer {
    override val order = 130

    override suspend fun run() = components.value.reconciler.initialLoad()
}

@ContributesIntoSet(AppScope::class)
@Inject
internal class DeferredDeletesInitializer(
    private val components: Lazy<DownloadComponents>,
    private val db: Lazy<NeutrodyneDatabase>,
    private val device: DeviceSettingsStore,
    private val queue: Lazy<DownloadWorkQueue>,
    @param:ApplicationScope private val scope: CoroutineScope,
) : AppInitializer {
    override val order = 310

    override suspend fun run() {
        scope.launch {
            db.value.playSessionDao().observeCurrentEpisodeId().collect {
                val downloads = components.value
                suspendRunCatching {
                    downloads.claimMutex.withLock { downloads.deletes.drain(it) }
                    downloads.cleanup.run(CleanupReason.PLAYED)
                    val target = device.get(DownloadSettingKeys.MOVE_TARGET)
                    if (target.isNotEmpty()) queue.value.move(target)
                }.onFailure { Log.e("Downloads", it) { "Deferred download deletion failed" } }
            }
        }
    }
}
