// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.datastore.DataStoreBindings
import ch.lkmc.neutrodyne.core.datastore.SettingStore
import ch.lkmc.neutrodyne.core.datastore.SettingsDataStoreFactory
import ch.lkmc.neutrodyne.core.datastore.SettingsStore
import ch.lkmc.neutrodyne.core.model.settings.GroupsSettingKeys
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 10 Settings sync: `DataStoreSettingsSyncPort` against a real store. */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreSettingsSyncPortTest {
    private lateinit var root: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        root =
            kotlin.io.path
                .createTempDirectory("nd-port-test")
                .toFile()
    }

    @AfterTest
    fun tearDown() {
        if (::scope.isInitialized) scope.cancel()
        root.deleteRecursively()
    }

    @Test
    fun aJsonNullResetsTheKey() =
        runTest {
            val (port, store) = portAndStore()
            store.set(GroupsSettingKeys.SHOW_UNGROUPED_TAB, true)
            assertTrue(port.applyRemote(GroupsSettingKeys.SHOW_UNGROUPED_TAB, "null"))
            assertFalse(store.get(GroupsSettingKeys.SHOW_UNGROUPED_TAB))
        }

    @Test
    fun aRemoteValueWritesDecoded() =
        runTest {
            val (port, store) = portAndStore()
            assertTrue(port.applyRemote(GroupsSettingKeys.SHOW_UNGROUPED_TAB, "true"))
            assertTrue(store.get(GroupsSettingKeys.SHOW_UNGROUPED_TAB))
        }

    @Test
    fun aCancelledApplyPropagates() =
        runTest {
            val (port, _) =
                portAndStore { delegate ->
                    object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> by
                    delegate {
                        override suspend fun updateData(
                            transform: suspend (
                                t: androidx.datastore.preferences.core.Preferences,
                            ) -> androidx.datastore.preferences.core.Preferences,
                        ): androidx.datastore.preferences.core.Preferences =
                            throw CancellationException("cancelled mid-write")
                    }
                }
            assertFailsWith<CancellationException> {
                port.applyRemote(GroupsSettingKeys.SHOW_UNGROUPED_TAB, "true")
            }
        }

    @Test
    fun aClearedRecorderStopsRecording() =
        runTest {
            val (port, _) = portAndStore()
            val calls = mutableListOf<String>()
            port.registerRecorder { _, json ->
                calls += json
                ch.lkmc.neutrodyne.core.domain.SettingWriteOrigin.Uncaptured
            }
            port.recordLocal(GroupsSettingKeys.SHOW_UNGROUPED_TAB, true)
            port.registerRecorder(null)
            port.recordLocal(GroupsSettingKeys.SHOW_UNGROUPED_TAB, false)
            assertEquals(listOf("true"), calls)
        }

    @Test
    fun aMaterializedListenerFailureDoesNotEscapeOrLoseTheLocalEvent() =
        runTest {
            val (port, store) = portAndStore()
            val events = mutableListOf<ch.lkmc.neutrodyne.core.domain.LocalSyncedSetting>()
            val collector =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    port.localSyncedChanges.collect { events += it }
                }
            port.registerMaterialized { _, _ -> throw java.io.IOException("Room bookkeeping unavailable") }
            store.set(GroupsSettingKeys.SHOW_UNGROUPED_TAB, true)
            port.emitLocal(GroupsSettingKeys.SHOW_UNGROUPED_TAB, true, 123L)
            assertTrue(store.get(GroupsSettingKeys.SHOW_UNGROUPED_TAB))
            assertEquals(listOf("true"), events.map { it.json })
            collector.cancel()
        }

    @Test
    fun anUnreadableFileIsNotARealDefaultValueForSync() =
        runTest {
            val (port, _) =
                portAndStore { delegate ->
                    object : DataStore<Preferences> by delegate {
                        override val data: Flow<Preferences> =
                            kotlinx.coroutines.flow.flow { throw java.io.IOException("cannot read") }
                    }
                }
            kotlin.test.assertNull(port.currentJson(GroupsSettingKeys.SHOW_UNGROUPED_TAB))
        }

    @Test
    fun aRecordedWriteCommitsTheOriginWithTheValueAndReset() =
        runTest {
            val (port, store) = portAndStore()
            val key = GroupsSettingKeys.SHOW_UNGROUPED_TAB
            val origin =
                ch.lkmc.neutrodyne.core.domain.SettingWriteOrigin
                    .Captured("historical intent")
            port.registerCommittedRecorder { _, _, _ -> origin }
            assertTrue(port.applyRecorded(key, "true", origin))
            assertTrue(store.get(key))
            assertEquals(origin, port.currentSnapshot(key)?.origin)
            assertTrue(port.applyRecorded(key, "null", origin))
            assertFalse(store.get(key))
            assertEquals(origin, port.currentSnapshot(key)?.origin)
        }

    @Test
    fun anUnavailableCommittedRecorderLeavesTheValueUntouched() =
        runTest {
            val (port, store) = portAndStore()
            val key = GroupsSettingKeys.SHOW_UNGROUPED_TAB
            store.set(key, true)
            assertFailsWith<okio.IOException> { port.recordCommitted(key, "false", port.currentSnapshot(key)!!) }
            port.registerCommittedRecorder { _, _, _ -> throw java.io.IOException("intent unavailable") }
            assertFailsWith<java.io.IOException> { port.recordCommitted(key, "false", port.currentSnapshot(key)!!) }
            assertTrue(store.get(key))
        }

    @Test
    fun aFailedRecordedWritePreservesThePreviousValueAndReceipt() =
        runTest {
            var failWrites = false
            val (port, store) =
                portAndStore { delegate ->
                    object : DataStore<Preferences> by delegate {
                        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                            if (failWrites) throw java.io.IOException("store unavailable")
                            return delegate.updateData(transform)
                        }
                    }
                }
            val key = GroupsSettingKeys.SHOW_UNGROUPED_TAB
            val prior =
                ch.lkmc.neutrodyne.core.domain.SettingWriteOrigin
                    .Captured("prior intent")
            port.registerCommittedRecorder { _, _, _ -> prior }
            assertTrue(port.applyRecorded(key, "true", prior))
            val before = port.currentSnapshot(key)
            port.registerCommittedRecorder { _, _, _ ->
                ch.lkmc.neutrodyne.core.domain.SettingWriteOrigin
                    .Captured("historical next intent")
            }
            failWrites = true
            assertFalse(
                port.applyRecorded(
                    key,
                    "false",
                    ch.lkmc.neutrodyne.core.domain.SettingWriteOrigin
                        .Captured("next"),
                ),
            )
            assertEquals(before, port.currentSnapshot(key))
            assertTrue(store.get(key))
        }

    @Test
    fun aFloatLiteralUsesTheTypedStoredRepresentation() =
        runTest {
            val (port, _) = portAndStore()
            val key = ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys.SPEED
            assertTrue(port.applyRemote(key, "1"))
            assertEquals("1.0", port.currentJson(key))
        }

    @Test
    fun canonicalJsonPreservesIntegerPrecisionAndResetIntent() =
        runTest {
            val (port, _) = portAndStore()
            val longKey =
                ch.lkmc.neutrodyne.core.model.settings.SettingKey
                    .Int64("probe.long", 0L, synced = true)
            for (literal in listOf(
                "9007199254740992",
                "9007199254740993",
                Long.MAX_VALUE.toString(),
                Long.MIN_VALUE.toString(),
            )) {
                assertEquals(literal, port.canonicalJson(longKey, literal))
            }
            val speed = ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys.SPEED
            assertEquals("1.0", port.canonicalJson(speed, "1"))
            assertEquals("1.0", port.canonicalJson(speed, "1e0"))
            assertEquals("null", port.canonicalJson(speed, "null"))
            assertEquals(null, port.canonicalJson(speed, "not-json"))
            assertEquals(null, port.canonicalJson(speed, "10000"))
        }

    private fun TestScope.portAndStore(
        wrap: (
            androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
        ) -> androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> = { it },
    ): Pair<DataStoreSettingsSyncPort, SettingsStore> {
        if (!::scope.isInitialized) {
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        }
        val dir = root.resolve("store-${root.list()?.size ?: 0}")
        val factory = SettingsDataStoreFactory(storagePathsFor(dir), scope, UnconfinedTestDispatcher(testScheduler))
        val store = DataStoreBindings.settingsStore(wrap(DataStoreBindings.portableDataStore(factory)))
        return DataStoreSettingsSyncPort(store, TestClock()) to store
    }

    private fun storagePathsFor(root: File): StoragePaths =
        StoragePaths(
            AppDirs(
                data = root.resolve("data").toPath(),
                config = root.resolve("config").toPath(),
                cache = root.resolve("cache").toPath(),
                state = root.resolve("state").toPath(),
                logs = root.resolve("logs").toPath(),
                downloadsDefault = root.resolve("downloads").toPath(),
            ),
        )
}
