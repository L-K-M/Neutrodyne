// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.datastore.DataStoreBindings
import ch.lkmc.neutrodyne.core.datastore.SettingsDataStoreFactory
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.LibraryDensity
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import ch.lkmc.neutrodyne.core.testing.SettingsRepositoryContract
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

    @Test fun everyDensitySurvivesReopeningOnlyThePortableFile() =
        runTest {
            for (density in LibraryDensity.entries) {
                val directory = root.resolve("density-${density.name}")
                val repository = repositoryOver(directory)
                assertEquals(LibraryDensity.MEDIUM, repository.get(Appearance.LIBRARY_DENSITY))
                repository.set(Appearance.LIBRARY_DENSITY, density)
                scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
                scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
                scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
                assertEquals(density, repositoryOver(directory).get(Appearance.LIBRARY_DENSITY))
                assertTrue(File(directory, "config/settings.preferences_pb").isFile)
                assertFalse(File(directory, "config/device_settings.preferences_pb").exists())
            }
        }

    private fun TestScope.repositoryOver(dir: File): DataStoreSettingsRepository {
        if (!::scope.isInitialized) {
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        }
        val factory = SettingsDataStoreFactory(storagePathsFor(dir), scope, UnconfinedTestDispatcher(testScheduler))
        return DataStoreSettingsRepository(
            settingsStore = DataStoreBindings.settingsStore(DataStoreBindings.portableDataStore(factory)),
            deviceSettingsStore = DataStoreBindings.deviceSettingsStore(DataStoreBindings.deviceDataStore(factory)),
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
