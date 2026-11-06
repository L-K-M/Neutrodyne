// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.datastore.DataStoreBindings
import ch.lkmc.neutrodyne.core.datastore.SettingsDataStoreFactory
import ch.lkmc.neutrodyne.core.datastore.SettingsStore
import ch.lkmc.neutrodyne.core.model.settings.GroupsSettingKeys
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys as Playback

/**
 * 10 Settings sync: `DataStoreSettingsSyncPort` against a real store, and
 * [SettingsSyncPort.syncedLiteralValues] over the real portable store (10 "Use this device's
 * library everywhere" step 3's bulk read): every synced key with its default or written value,
 * never a device-local key.
 */
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
            port.registerRecorder { _, json -> calls += json }
            port.recordLocal(GroupsSettingKeys.SHOW_UNGROUPED_TAB, true)
            port.registerRecorder(null)
            port.recordLocal(GroupsSettingKeys.SHOW_UNGROUPED_TAB, false)
            assertEquals(listOf("true"), calls)
        }

    @Test
    fun everySyncedKeyCarriesItsLiteralDefaultValue() =
        runTest {
            val port = portOver(root.resolve("defaults"))
            val values = port.syncedLiteralValues()

            val synced =
                ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys.list
                    .filter { it.synced }
            assertEquals(synced.map { it.name }.toSet(), values.keys.map { it.name }.toSet())
            assertEquals(Playback.SMART_RESUME.default.toString(), values[Playback.SMART_RESUME])
            assertEquals(Playback.SPEED.default.toString(), values[Playback.SPEED])
        }

    @Test
    fun writtenValuesReplaceTheirDefaults() =
        runTest {
            val dir = root.resolve("written")
            val factory = factoryOver(dir)
            // One store instance for both readers; a second DataStore on the file would throw.
            val portable =
                DataStoreBindings.settingsStore(DataStoreBindings.portableDataStore(factory))
            val port = DataStoreSettingsSyncPort(settingsStore = portable, clock = TestClock())
            val repository =
                DataStoreSettingsRepository(
                    settingsStore = portable,
                    deviceSettingsStore =
                        DataStoreBindings.deviceSettingsStore(
                            DataStoreBindings.deviceDataStore(factory),
                        ),
                    syncPort =
                        ch.lkmc.neutrodyne.core.testing
                            .FakeSettingsSyncPort(),
                    clock = TestClock(),
                )

            repository.set(Playback.SPEED, 1.5f)

            assertEquals("1.5", port.syncedLiteralValues()[Playback.SPEED])
            assertTrue(
                port.syncedLiteralValues().keys.none {
                    it.file ==
                        ch.lkmc.neutrodyne.core.model.settings.SettingsFile.DEVICE
                },
                "device-local keys never join the bulk read",
            )
        }

    private fun TestScope.portAndStore(
        wrap: (
            androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
        ) -> androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> = { it },
    ): Pair<DataStoreSettingsSyncPort, SettingsStore> {
        val factory = factoryOver(root.resolve("store-${root.list()?.size ?: 0}"))
        val store = DataStoreBindings.settingsStore(wrap(DataStoreBindings.portableDataStore(factory)))
        return DataStoreSettingsSyncPort(store, TestClock()) to store
    }

    private fun TestScope.portOver(dir: File): DataStoreSettingsSyncPort {
        val factory = factoryOver(dir)
        return DataStoreSettingsSyncPort(
            settingsStore = DataStoreBindings.settingsStore(DataStoreBindings.portableDataStore(factory)),
            clock = TestClock(),
        )
    }

    /** One DataStore per file per test JVM (01 rule 3): one shared factory per directory. */
    private fun TestScope.factoryOver(dir: File): SettingsDataStoreFactory {
        if (!::scope.isInitialized) {
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        }
        return SettingsDataStoreFactory(storagePathsFor(dir), scope, UnconfinedTestDispatcher(testScheduler))
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
