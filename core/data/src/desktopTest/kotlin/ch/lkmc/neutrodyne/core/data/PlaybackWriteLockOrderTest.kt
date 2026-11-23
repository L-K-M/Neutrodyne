// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.data.settings.ScopeSettingsRepositoryImpl
import ch.lkmc.neutrodyne.core.datastore.DataStoreBindings
import ch.lkmc.neutrodyne.core.domain.SettingWriteOrigin
import ch.lkmc.neutrodyne.core.domain.SettingsWriteCoordinator
import ch.lkmc.neutrodyne.core.model.PlaybackScopeWrite
import ch.lkmc.neutrodyne.core.model.PlaybackSettingChange
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.testing.FakeGroupChannelSync
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSnapshotScheduler
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A sync round holds a setting's write lock while it waits for the Room writer. A global player
 * write must take the two in the same order, or neither finishes and every later write waits too.
 */
class PlaybackWriteLockOrderTest {
    private val clock = TestClock()
    private val db = TestDb.inMemory(clock = clock)

    @AfterTest fun close() = db.close()

    @Test fun aGlobalSpeedWriteCannotDeadlockWithASyncRound() =
        globalWriteDuringARound(PlaybackSettingChange.Speed(1.5f), PlaybackSettingKeys.SPEED)

    @Test fun aGlobalSilenceWriteCannotDeadlockWithASyncRound() =
        globalWriteDuringARound(PlaybackSettingChange.SkipSilence(true), PlaybackSettingKeys.SKIP_SILENCE)

    private fun globalWriteDuringARound(
        change: PlaybackSettingChange,
        key: SettingKey<*>,
    ) = runBlocking {
        val store = DataStoreBindings.settingsStore(MemoryPreferences())
        val real = DataStoreSettingsWriteCoordinator { db }
        val locks = ObservedLocks(real)
        val port = DataStoreSettingsSyncPort(store, clock)
        // SettingsCapture's callbacks open their own transactions inside the player's.
        port.registerRecorder { _, _ ->
            db.withWriteTransaction { db.syncStateDao().get() }
            SettingWriteOrigin.Uncaptured
        }
        port.registerMaterialized { _, _ -> db.withWriteTransaction { db.syncStateDao().get() } }
        val settings =
            DataStoreSettingsRepository(
                settingsStore = store,
                deviceSettingsStore = DataStoreBindings.deviceSettingsStore(MemoryPreferences()),
                syncPort = port,
                clock = clock,
                writeLocks = locks,
            )
        val repository =
            ScopeSettingsRepositoryImpl(
                db,
                settings,
                { FakeRefreshController() },
                FakeGroupChannelSync(),
                FakeSnapshotScheduler(),
                FakeYouTubeCapabilitiesSource(),
            )
        val podcast = db.podcastDao().insertPodcast(podcastEntity())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val held = CompletableDeferred<Unit>()
        val round =
            scope.async {
                // The order of SettingsCapture's baseline write: key lock, then Room writer.
                real.withLock(key) {
                    held.complete(Unit)
                    locks.requested.await()
                    db.withWriteTransaction { db.syncStateDao().get() }
                }
            }
        held.await()
        val write = scope.async { repository.writePlayback(podcast, null, SettingScope.GLOBAL, change) }
        try {
            val result = withTimeoutOrNull(LIMIT_MS) { round.await().let { write.await() } }
            assertNotNull(result, "the write and the round deadlocked")
            assertEquals(Outcome.Success(PlaybackScopeWrite.APPLIED), result)
        } finally {
            // The player write is non-cancellable: release the round first so it can unwind.
            round.cancel()
            write.join()
        }
    }

    private class ObservedLocks(
        private val real: SettingsWriteCoordinator,
    ) : SettingsWriteCoordinator {
        val requested = CompletableDeferred<Unit>()

        override suspend fun <T> withLock(
            key: SettingKey<*>,
            block: suspend () -> T,
        ): T {
            requested.complete(Unit)
            return real.withLock(key, block)
        }
    }

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private companion object {
        const val LIMIT_MS = 10_000L
    }
}
