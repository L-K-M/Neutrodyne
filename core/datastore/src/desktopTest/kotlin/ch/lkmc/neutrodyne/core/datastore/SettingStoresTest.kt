// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.datastore

import app.cash.turbine.test
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.LogLevel
import ch.lkmc.neutrodyne.core.common.LogSink
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The two stores over real DataStore files on a temp dir (01 Testing: "DataStore stores, JVM"):
 * round trip per type, defaults, file routing and independence, corrupt-file recovery with the
 * WARN log, and the never-throw read rules (wrong-typed value, unknown Choice name).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingStoresTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val logRecords = mutableListOf<Triple<LogLevel, String, String>>()
    private lateinit var scope: CoroutineScope
    private lateinit var root: File

    private val portableLong = SettingKey.Int64("playback.skip_back_ms", 10_000, SettingsFile.PORTABLE)
    private val portableBool = SettingKey.Bool("feeds.notify_new_episodes", false, SettingsFile.PORTABLE)
    private val portableInt = SettingKey.Int32("playback.sleep_last_minutes", 15, SettingsFile.PORTABLE)
    private val portableFloat = SettingKey.Float32("playback.speed", 1.0f, SettingsFile.PORTABLE)
    private val portableText = SettingKey.Text("sync.server_url", "", SettingsFile.PORTABLE)
    private val portableTextSet =
        SettingKey.TextSet("playback.speed_presets", persistentSetOf("1.25"), SettingsFile.PORTABLE)

    private val deviceText = SettingKey.Text("ui.feeds_selected_source", "all", SettingsFile.DEVICE)
    private val deviceInt = SettingKey.Int32("downloads.planner_baseline_at", 0, SettingsFile.DEVICE)

    private enum class LibrarySegment { PODCASTS, GROUPS }

    private val deviceChoice =
        SettingKey.Choice(
            "ui.library_segment",
            LibrarySegment.PODCASTS,
            persistentListOf(LibrarySegment.PODCASTS, LibrarySegment.GROUPS),
            SettingsFile.DEVICE,
        )

    @BeforeTest
    fun setUp() {
        Log.install(LogSink { level, tag, message -> logRecords += Triple(level, tag, message) })
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        root =
            kotlin.io.path
                .createTempDirectory("nd-datastore-test")
                .toFile()
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        Log.install()
    }

    @Test
    fun roundTripsEveryKeyType() =
        runTest(dispatcher) {
            val (portable, device) = newStores()

            portable.set(portableLong, 30_000L)
            portable.set(portableBool, true)
            portable.set(portableInt, 45)
            portable.set(portableFloat, 1.5f)
            portable.set(portableText, "https://sync.example.org")
            portable.set(portableTextSet, persistentSetOf("1.75", "2.0"))
            device.set(deviceText, "group:abc")
            device.set(deviceInt, 42)
            device.set(deviceChoice, LibrarySegment.GROUPS)

            assertEquals(30_000L, portable.get(portableLong))
            assertEquals(true, portable.get(portableBool))
            assertEquals(45, portable.get(portableInt))
            assertEquals(1.5f, portable.get(portableFloat))
            assertEquals("https://sync.example.org", portable.get(portableText))
            assertEquals(persistentSetOf("1.75", "2.0"), portable.get(portableTextSet))
            assertEquals("group:abc", device.get(deviceText))
            assertEquals(42, device.get(deviceInt))
            assertEquals(LibrarySegment.GROUPS, device.get(deviceChoice))
        }

    @Test
    fun absentKeysReadTheirDefaults() =
        runTest(dispatcher) {
            val (portable, device) = newStores()

            assertEquals(10_000L, portable.get(portableLong))
            assertEquals(false, portable.get(portableBool))
            assertEquals(15, portable.get(portableInt))
            assertEquals(1.0f, portable.get(portableFloat))
            assertEquals("", portable.get(portableText))
            assertEquals(persistentSetOf("1.25"), portable.get(portableTextSet))
            assertEquals("all", device.get(deviceText))
            assertEquals(0, device.get(deviceInt))
            assertEquals(LibrarySegment.PODCASTS, device.get(deviceChoice))
        }

    @Test
    fun updatesTransformAtomically() =
        runTest(dispatcher) {
            val (portable, _) = newStores()

            portable.update(portableLong) { it + 5_000 }

            assertEquals(15_000L, portable.get(portableLong))
        }

    @Test
    fun resetRestoresTheDefault() =
        runTest(dispatcher) {
            val (portable, _) = newStores()
            portable.set(portableText, "https://sync.example.org")

            portable.reset(portableText)

            assertEquals("", portable.get(portableText))
        }

    @Test
    fun observeEmitsTheDefaultThenEveryChange() =
        runTest(dispatcher) {
            val (portable, _) = newStores()

            portable.observe(portableText).test {
                assertEquals("", awaitItem())
                portable.set(portableText, "https://sync.example.org")
                assertEquals("https://sync.example.org", awaitItem())
                portable.reset(portableText)
                assertEquals("", awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun keysOfTheOtherFileAreRejected() =
        runTest(dispatcher) {
            val (portable, device) = newStores()

            assertFailsWith<IllegalArgumentException> { portable.get(deviceText) }
            assertFailsWith<IllegalArgumentException> { device.set(portableBool, true) }
        }

    @Test
    fun theTwoFilesAreIndependent() =
        runTest(dispatcher) {
            val (portable, device) = newStores()
            val deviceKeyWithPortableName = SettingKey.Bool(portableBool.name, false, SettingsFile.DEVICE)

            portable.set(portableBool, true)
            device.set(deviceKeyWithPortableName, true)
            portable.reset(portableBool)

            assertEquals(false, portable.get(portableBool))
            assertEquals(true, device.get(deviceKeyWithPortableName))
        }

    @Test
    fun eachWriteCreatesOnlyItsOwnFile() =
        runTest(dispatcher) {
            val (portable, device) = newStores()
            val configDir = root.resolve("config")

            portable.set(portableText, "x")
            assertTrue(File(configDir, "settings.preferences_pb").isFile)
            assertEquals(false, File(configDir, "device_settings.preferences_pb").exists())

            device.set(deviceText, "y")
            assertTrue(File(configDir, "device_settings.preferences_pb").isFile)
        }

    @Test
    fun wrongTypedStoredValueReadsAsTheDefault() =
        runTest(dispatcher) {
            val (portable, _) = newStores()
            portable.set(portableInt, 45)

            // Same name re-typed as Float: the stored Int must not leak into the read.
            val reRead = SettingKey.Float32(portableInt.name, 2.5f, SettingsFile.PORTABLE)

            assertEquals(2.5f, portable.get(reRead))
        }

    @Test
    fun unknownStoredChoiceNameReadsAsTheDefault() =
        runTest(dispatcher) {
            val (_, device) = newStores()
            val storedAsText = SettingKey.Text(deviceChoice.name, "", SettingsFile.DEVICE)

            device.set(storedAsText, "RETIRED_CONSTANT")

            assertEquals(LibrarySegment.PODCASTS, device.get(deviceChoice))
        }

    @Test
    fun corruptFileResetsToDefaultsAndLogsWarn() =
        runTest(dispatcher) {
            val file = File(root.resolve("config"), "settings.preferences_pb")
            file.parentFile.mkdirs()
            file.writeBytes(byteArrayOf(0x0a, 0x7f, -0x21, 0x03))

            val (portable, _) = newStores()

            assertEquals("", portable.get(portableText))
            assertTrue(
                logRecords.any { it.first == LogLevel.WARN && it.second == "DataStore" },
                "expected a WARN record for the corrupt file, got: $logRecords",
            )

            // The reset file accepts writes again.
            portable.set(portableText, "https://sync.example.org")
            assertEquals("https://sync.example.org", portable.get(portableText))
        }

    @Test
    fun choiceValueOutsideItsValuesIsRejected() =
        runTest(dispatcher) {
            val (_, device) = newStores()
            val restricted =
                SettingKey.Choice(
                    "ui.density",
                    LibrarySegment.PODCASTS,
                    persistentListOf(LibrarySegment.PODCASTS),
                    SettingsFile.DEVICE,
                )

            assertFailsWith<IllegalArgumentException> { device.set(restricted, LibrarySegment.GROUPS) }
            assertEquals(LibrarySegment.PODCASTS, device.get(restricted))
        }

    @Test
    fun theFactoryCreatesEachFileAtItsStoragePath() =
        runTest(dispatcher) {
            val paths = storagePathsFor(root)
            val factory = SettingsDataStoreFactory(paths, scope, dispatcher)

            val portable = SettingsStore(factory.create(SettingsFile.PORTABLE))
            portable.set(portableText, "x")
            assertTrue(File(paths.dataStoreFile(SettingsFile.PORTABLE.storeName)).isFile)

            val device = DeviceSettingsStore(factory.create(SettingsFile.DEVICE))
            device.set(deviceText, "y")
            assertTrue(File(paths.dataStoreFile(SettingsFile.DEVICE.storeName)).isFile)
        }

    /** A fresh store pair on the shared temp dir; every test opens each path exactly once. */
    private fun newStores(): Pair<SettingsStore, DeviceSettingsStore> {
        val factory = SettingsDataStoreFactory(storagePathsFor(root), scope, dispatcher)
        return DataStoreBindings.settingsStore(DataStoreBindings.portableDataStore(factory)) to
            DataStoreBindings.deviceSettingsStore(DataStoreBindings.deviceDataStore(factory))
    }

    /** Desktop [StoragePaths] over a temp root: DataStore files live under `config/`. */
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
