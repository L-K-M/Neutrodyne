// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.data.DataStoreSettingsRepository
import ch.lkmc.neutrodyne.core.data.DataStoreSettingsSyncPort
import ch.lkmc.neutrodyne.core.data.DataStoreSettingsWriteCoordinator
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.datastore.DataStoreBindings
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.feeds.backup.SettingValueV1
import ch.lkmc.neutrodyne.feeds.backup.SettingsV1
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 01's Sync write ordering at the linked-restore boundary: a local `set`/`reset` records its
 * newer Room intent and then writes DataStore, while [RestoreSettingsMerge]'s linked historical
 * path records its archive intent and then calls `applyRemote`. A local write that interleaves
 * between the restore's Room capture and its DataStore write must not leave the newer pending
 * literal beside the older stored value.
 *
 * The regression is driven through the production repository, port, merge and Room, with a real
 * in-memory database on the test scheduler and an instrumented DataStore that pauses each write
 * at its commit. The scheduler makes the interleaving deterministic: the restore runs to its
 * DataStore gate first, the local write runs to its own gate second, and the test then chooses
 * the commit order. Before the fix the local write reaches its gate and the restore's older
 * value commits last; after it the local write waits for the restore's whole accept+persist.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoreSettingsWriteOrderTest {
    private val clock = TestClock()
    private lateinit var db: NeutrodyneDatabase

    @AfterTest
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun aLocalSetDuringALinkedRestoreCannotLeaveNewerPendingLiteralWithOlderStoredValue() =
        runTest {
            val dataStore = GatedPreferencesDataStore()
            val (repository, merge) = components(dataStore)
            link()
            val archiveSpeed = 0.5f
            val localSpeed = 2.0f

            val restoreJob =
                backgroundScope.launch {
                    merge.apply(
                        SettingsV1(
                            listOf(
                                SettingValueV1(
                                    PlaybackSettingKeys.SPEED.name,
                                    "Float32",
                                    value = JsonPrimitive(archiveSpeed),
                                ),
                            ),
                        ),
                        linkedArchiveCreatedAt = ARCHIVE_CREATED_AT,
                    )
                }
            testScheduler.runCurrent()
            assertTrue(
                dataStore.restoreWriteReached,
                "the restore must capture Room and reach its DataStore write before the local write",
            )

            val localJob =
                backgroundScope.launch {
                    repository.set(PlaybackSettingKeys.SPEED, localSpeed)
                }
            testScheduler.runCurrent()

            if (dataStore.localWriteReached) {
                // No serialization: the local write reached its commit gate, so the older
                // stored value commits last.
                dataStore.localGate.complete(Unit)
                testScheduler.runCurrent()
                dataStore.restoreGate.complete(Unit)
                testScheduler.runCurrent()
            } else {
                // Serialized: the local write waits for the restore's whole accept+persist,
                // then commits its newer value after it.
                dataStore.restoreGate.complete(Unit)
                testScheduler.runCurrent()
                dataStore.localGate.complete(Unit)
                testScheduler.runCurrent()
            }

            restoreJob.join()
            localJob.join()

            val row =
                db
                    .syncOutboxDao()
                    .rows()
                    .first { it.coll == "setting" && it.rid == PlaybackSettingKeys.SPEED.name }
            assertEquals(localSpeed, dataStore.preferences.value[SPEED_KEY])
            assertEquals("2.0", row.value)
        }

    @Test
    fun aLocalResetDuringALinkedRestoreCannotLeaveAResetPendingLiteralWithAStoredValue() =
        runTest {
            val dataStore = GatedPreferencesDataStore()
            val (repository, merge) = components(dataStore)
            link()
            val archiveSpeed = 0.5f

            val restoreJob =
                backgroundScope.launch {
                    merge.apply(
                        SettingsV1(
                            listOf(
                                SettingValueV1(
                                    PlaybackSettingKeys.SPEED.name,
                                    "Float32",
                                    value = JsonPrimitive(archiveSpeed),
                                ),
                            ),
                        ),
                        linkedArchiveCreatedAt = ARCHIVE_CREATED_AT,
                    )
                }
            testScheduler.runCurrent()
            assertTrue(dataStore.restoreWriteReached)

            val localJob =
                backgroundScope.launch {
                    repository.reset(PlaybackSettingKeys.SPEED)
                }
            testScheduler.runCurrent()

            if (dataStore.localResetReached) {
                dataStore.localResetGate.complete(Unit)
                testScheduler.runCurrent()
                dataStore.restoreGate.complete(Unit)
                testScheduler.runCurrent()
            } else {
                dataStore.restoreGate.complete(Unit)
                testScheduler.runCurrent()
                dataStore.localResetGate.complete(Unit)
                testScheduler.runCurrent()
            }

            restoreJob.join()
            localJob.join()

            val row =
                db
                    .syncOutboxDao()
                    .rows()
                    .first { it.coll == "setting" && it.rid == PlaybackSettingKeys.SPEED.name }
            assertEquals(PlaybackSettingKeys.SPEED.default, repository.get(PlaybackSettingKeys.SPEED))
            assertEquals("null", row.value)
        }

    /**
     * The production repository, port and merge over [dataStore], with the linked recorder
     * `SettingsCapture` installs: the local Room intent lands before the DataStore write.
     */
    private fun TestScope.components(
        dataStore: GatedPreferencesDataStore,
    ): Pair<DataStoreSettingsRepository, RestoreSettingsMerge> {
        db = TestDb.inMemory(clock = clock, queryContext = StandardTestDispatcher(testScheduler))
        val deviceStore = SimplePreferencesDataStore()
        val settingsStore = DataStoreBindings.settingsStore(dataStore)
        val port = DataStoreSettingsSyncPort(settingsStore, clock)
        port.registerRecorder { key, json ->
            db.withWriteTransaction {
                db.syncOutboxDao().captureIntent("setting", key.name, "v", json)
            }
        }
        val writeLocks = DataStoreSettingsWriteCoordinator()
        val repository =
            DataStoreSettingsRepository(
                settingsStore = settingsStore,
                deviceSettingsStore = DataStoreBindings.deviceSettingsStore(deviceStore),
                syncPort = port,
                clock = clock,
                writeLocks = writeLocks,
            )
        return repository to RestoreSettingsMerge(repository, db, port, writeLocks)
    }

    /** A linked fixture: capture enabled, a node, a clock, `linkedAt` set. */
    private suspend fun link() {
        db.syncStateDao().upsert(
            SyncStateEntity(
                serverUrl = "https://sync.example.com",
                enabled = true,
                nodeId = "0000000000000001",
                hlc = 1L shl 16,
                linkedAt = 1L,
            ),
        )
    }

    private companion object {
        /** An archive older than the linked fixture's clock, so the local intent is newer. */
        const val ARCHIVE_CREATED_AT = 1_780_000_000_000L
        val SPEED_KEY = floatPreferencesKey(PlaybackSettingKeys.SPEED.name)
    }
}

/**
 * A [DataStore] fixture that pauses each settings write at its commit: the restore's archive
 * value, a local value, and a local reset (the key's absence). The test scheduler, not a sleep,
 * decides when each gate opens.
 */
private class GatedPreferencesDataStore : DataStore<Preferences> {
    val preferences = MutableStateFlow(emptyPreferences())

    @Volatile var restoreWriteReached = false
    @Volatile var localWriteReached = false
    @Volatile var localResetReached = false

    val restoreGate = CompletableDeferred<Unit>()
    val localGate = CompletableDeferred<Unit>()
    val localResetGate = CompletableDeferred<Unit>()

    override val data: Flow<Preferences> = preferences

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val next = transform(preferences.value)
        when (next[floatPreferencesKey("playback.speed")]) {
            0.5f -> {
                restoreWriteReached = true
                restoreGate.await()
            }

            2.0f -> {
                localWriteReached = true
                localGate.await()
            }

            null -> {
                localResetReached = true
                localResetGate.await()
            }
        }
        preferences.value = next
        return next
    }
}

/** A plain in-memory [DataStore] for the device file, which these tests never write. */
private class SimplePreferencesDataStore : DataStore<Preferences> {
    private val preferences = MutableStateFlow(emptyPreferences())

    override val data: Flow<Preferences> = preferences

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val next = transform(preferences.value)
        preferences.value = next
        return next
    }
}
