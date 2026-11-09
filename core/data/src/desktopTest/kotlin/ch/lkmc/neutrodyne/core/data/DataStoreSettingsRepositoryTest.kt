// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.datastore.DataStoreBindings
import ch.lkmc.neutrodyne.core.datastore.SettingsDataStoreFactory
import ch.lkmc.neutrodyne.core.domain.SettingWriteOrigin
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.LibraryDensity
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import ch.lkmc.neutrodyne.core.testing.SettingsRepositoryContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys as Appearance

/**
 * The DataStore-backed [SettingsRepository] against the shared contract, plus what only the real
 * implementation can show: writes route to the file of their key, and an unwritable DataStore
 * path turns into [SettingsError.WriteFailed].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreSettingsRepositoryTest : SettingsRepositoryContract() {
    private lateinit var root: File
    private lateinit var scope: CoroutineScope
    private var repositoriesBuilt = 0

    @BeforeTest
    fun setUp() {
        root =
            kotlin.io.path
                .createTempDirectory("nd-data-test")
                .toFile()
    }

    @AfterTest
    fun tearDown() {
        if (::scope.isInitialized) scope.cancel()
        root.deleteRecursively()
    }

    /** Fresh directory per repository: one DataStore per file path per test JVM (01 rule 3). */
    override fun TestScope.createRepository(): SettingsRepository =
        repositoryOver(root.resolve("repo-${repositoriesBuilt++}"))

    @Test
    fun writesRouteToTheFileOfTheirKey() =
        runTest {
            val repository = repositoryOver(root.resolve("repo-routing"))
            val configDir = root.resolve("repo-routing/config")

            repository.set(portableText, "https://sync.example.org")
            assertTrue(File(configDir, "settings.preferences_pb").isFile)
            assertFalse(File(configDir, "device_settings.preferences_pb").exists())

            repository.set(deviceText, "ungrouped")
            assertTrue(File(configDir, "device_settings.preferences_pb").isFile)
        }

    @Test
    fun anUnwritablePathReportsWriteFailed() =
        runTest {
            val brokenRoot = root.resolve("repo-broken").apply { mkdirs() }
            // A regular file where the DataStore directory must go: every write must fail on IO.
            File(brokenRoot, "config").writeText("in the way")

            val repository = repositoryOver(brokenRoot)

            assertEquals(Outcome.Failure(SettingsError.WriteFailed), repository.set(portableText, "x"))
        }

    @Test
    fun aRecorderFailureReportsWriteFailed() =
        runTest {
            val port =
                object : ch.lkmc.neutrodyne.core.domain.SettingsSyncPort by
                ch.lkmc.neutrodyne.core.testing
                    .FakeSettingsSyncPort() {
                    override suspend fun recordLocal(
                        key: ch.lkmc.neutrodyne.core.model.settings.SettingKey<*>,
                        value: Any?,
                    ): SettingWriteOrigin = throw java.io.IOException("recorder down")
                }
            val dir = root.resolve("repo-recorder")
            if (!::scope.isInitialized) {
                scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
            }
            val factory =
                SettingsDataStoreFactory(storagePathsFor(dir), scope, UnconfinedTestDispatcher(testScheduler))
            val repository =
                DataStoreSettingsRepository(
                    settingsStore = DataStoreBindings.settingsStore(DataStoreBindings.portableDataStore(factory)),
                    deviceSettingsStore =
                        DataStoreBindings.deviceSettingsStore(DataStoreBindings.deviceDataStore(factory)),
                    syncPort = port,
                    clock =
                        ch.lkmc.neutrodyne.core.testing
                            .TestClock(),
                    writeLocks = DataStoreSettingsWriteCoordinator(),
                )

            assertEquals(Outcome.Failure(SettingsError.WriteFailed), repository.set(portableSyncedLong, 30_000L))
            assertEquals(10_000L, repository.get(portableSyncedLong))
        }

    @Test
    fun postWriteBookkeepingFailureStillReturnsSuccessForTheCommittedValue() =
        runTest {
            if (!::scope.isInitialized) {
                scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
            }
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val factory = SettingsDataStoreFactory(storagePathsFor(root.resolve("post-write")), scope, dispatcher)
            val store = DataStoreBindings.settingsStore(DataStoreBindings.portableDataStore(factory))
            val clock =
                ch.lkmc.neutrodyne.core.testing
                    .TestClock()
            val port = DataStoreSettingsSyncPort(store, clock)
            port.registerMaterialized { _, _ -> throw java.io.IOException("bookkeeping unavailable") }
            val repository =
                DataStoreSettingsRepository(
                    store,
                    DataStoreBindings.deviceSettingsStore(DataStoreBindings.deviceDataStore(factory)),
                    port,
                    clock,
                    DataStoreSettingsWriteCoordinator(),
                )
            assertEquals(Outcome.Success(Unit), repository.set(portableSyncedLong, 20000L))
            assertEquals(20000L, repository.get(portableSyncedLong))
        }

    @Test
    fun aCapturedReceiptSurvivesReopenWithoutItsRoomCallback() =
        runTest {
            val dir = root.resolve("captured-receipt")
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            scope = CoroutineScope(SupervisorJob() + dispatcher)
            val factory = SettingsDataStoreFactory(storagePathsFor(dir), scope, dispatcher)
            val store = DataStoreBindings.settingsStore(DataStoreBindings.portableDataStore(factory))
            val clock =
                ch.lkmc.neutrodyne.core.testing
                    .TestClock()
            val port = DataStoreSettingsSyncPort(store, clock)
            port.registerRecorder { _, _ -> SettingWriteOrigin.Captured("admitted-before-gate-flip") }
            port.registerMaterialized { _, _ -> throw java.io.IOException("Room unavailable after commit") }
            val repository =
                DataStoreSettingsRepository(
                    store,
                    DataStoreBindings.deviceSettingsStore(DataStoreBindings.deviceDataStore(factory)),
                    port,
                    clock,
                    DataStoreSettingsWriteCoordinator(),
                )
            assertEquals(Outcome.Success(Unit), repository.set(portableSyncedLong, 20000L))
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
            scope = CoroutineScope(SupervisorJob() + dispatcher)
            val reopenedFactory = SettingsDataStoreFactory(storagePathsFor(dir), scope, dispatcher)
            val reopened = DataStoreBindings.settingsStore(DataStoreBindings.portableDataStore(reopenedFactory))
            val reopenedPort = DataStoreSettingsSyncPort(reopened, clock)
            assertEquals("20000", reopenedPort.currentSnapshot(portableSyncedLong)?.json)
            assertEquals(
                SettingWriteOrigin.Captured("admitted-before-gate-flip"),
                reopenedPort.currentSnapshot(portableSyncedLong)?.origin,
            )
        }

    @Test
    fun anUncapturedWritePersistsItsOriginWithoutRoomBookkeeping() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            scope = CoroutineScope(SupervisorJob() + dispatcher)
            val factory = SettingsDataStoreFactory(storagePathsFor(root.resolve("off-receipt")), scope, dispatcher)
            val store = DataStoreBindings.settingsStore(DataStoreBindings.portableDataStore(factory))
            val clock =
                ch.lkmc.neutrodyne.core.testing
                    .TestClock()
            val port = DataStoreSettingsSyncPort(store, clock)
            port.registerRecorder { _, _ -> SettingWriteOrigin.Uncaptured }
            port.registerMaterialized { _, _ -> throw java.io.IOException("Room unavailable") }
            val repository =
                DataStoreSettingsRepository(
                    store,
                    DataStoreBindings.deviceSettingsStore(DataStoreBindings.deviceDataStore(factory)),
                    port,
                    clock,
                    DataStoreSettingsWriteCoordinator(),
                )
            assertEquals(Outcome.Success(Unit), repository.set(portableSyncedLong, 15000L))
            assertEquals("15000", port.currentSnapshot(portableSyncedLong)?.json)
            assertEquals(SettingWriteOrigin.Uncaptured, port.currentSnapshot(portableSyncedLong)?.origin)
            repository.reset(portableSyncedLong)
            assertEquals("10000", port.currentSnapshot(portableSyncedLong)?.json)
            assertEquals(SettingWriteOrigin.Uncaptured, port.currentSnapshot(portableSyncedLong)?.origin)
            val portableNames =
                ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys.list
                    .filter { it.file == SettingsFile.PORTABLE }
                    .map { it.name }
                    .toSet()
            assertEquals(portableNames, repository.observePortableSnapshot().first().keys)
        }

    @Test fun appearanceValuesPersistAndStayOutOfSync() =
        runTest {
            val repository = repositoryOver(root.resolve("appearance"))
            repository.set(Appearance.PURE_BLACK, true)
            repository.set(Appearance.ARTWORK_TINT, false)
            repository.set(Appearance.LIBRARY_DENSITY, LibraryDensity.LARGE)
            repository.set(Appearance.PLAYER_PANEL_HIDDEN, true)
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
            val reopened = repositoryOver(root.resolve("appearance"))
            assertTrue(reopened.get(Appearance.PURE_BLACK))
            assertFalse(reopened.get(Appearance.ARTWORK_TINT))
            assertEquals(LibraryDensity.LARGE, reopened.get(Appearance.LIBRARY_DENSITY))
            assertTrue(reopened.get(Appearance.PLAYER_PANEL_HIDDEN))
            assertTrue(
                ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys.ALL
                    .none { it.synced },
            )
        }

    private fun TestScope.repositoryOver(dir: File): DataStoreSettingsRepository {
        if (!::scope.isInitialized) {
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        }
        val factory = SettingsDataStoreFactory(storagePathsFor(dir), scope, UnconfinedTestDispatcher(testScheduler))
        return DataStoreSettingsRepository(
            settingsStore = DataStoreBindings.settingsStore(DataStoreBindings.portableDataStore(factory)),
            deviceSettingsStore = DataStoreBindings.deviceSettingsStore(DataStoreBindings.deviceDataStore(factory)),
            syncPort =
                ch.lkmc.neutrodyne.core.testing
                    .FakeSettingsSyncPort(),
            clock =
                ch.lkmc.neutrodyne.core.testing
                    .TestClock(),
            writeLocks = DataStoreSettingsWriteCoordinator(),
        )
    }

    /** Desktop [StoragePaths] over a temp root; DataStore files land under `config/`. */
    private fun storagePathsFor(root: File): StoragePaths {
        val dirs =
            AppDirs(
                data = root.resolve("data").toPath(),
                config = root.resolve("config").toPath(),
                cache = root.resolve("cache").toPath(),
                state = root.resolve("state").toPath(),
                logs = root.resolve("logs").toPath(),
                downloadsDefault = root.resolve("downloads").toPath(),
            )
        return StoragePaths(dirs)
    }
}
