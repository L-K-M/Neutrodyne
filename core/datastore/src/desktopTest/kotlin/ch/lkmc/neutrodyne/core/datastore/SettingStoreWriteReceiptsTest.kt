// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.StoragePaths
import ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.SettingsFile
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.IOException
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Per-key write receipts over real DataStore files on a temp dir: value+receipt atomicity (a
 * successful commit, a file reopen, a failed commit), receipt clearing on untracked writes,
 * the strict snapshot read, file routing and value validation rejections, and the receipt
 * namespace staying outside every setting name.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingStoreWriteReceiptsTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var scope: CoroutineScope
    private lateinit var root: File

    private val portableText = SettingKey.Text("sync.server_url", "", SettingsFile.PORTABLE)
    private val portableLong = SettingKey.Int64("playback.skip_back_ms", 10_000, SettingsFile.PORTABLE)
    private val deviceText = SettingKey.Text("ui.feeds_selected_source", "all", SettingsFile.DEVICE)

    private enum class Panel { FEEDS, QUEUE }

    private val restrictedChoice =
        SettingKey.Choice(
            "playback.panel_mode",
            Panel.FEEDS,
            persistentListOf(Panel.FEEDS),
            SettingsFile.PORTABLE,
        )

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        root =
            kotlin.io.path
                .createTempDirectory("nd-datastore-receipt-test")
                .toFile()
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    @Test
    fun setWithReceiptCommitsTheValueAndReceiptTogether() =
        runTest(dispatcher) {
            val store = newStore(scope)

            store.setWithReceipt(portableText, "https://sync.example.org", "r1")

            assertEquals(
                SettingSnapshot("https://sync.example.org", "r1"),
                store.readWithReceipt(portableText),
            )
            // The receipt is metadata: plain reads still see only the value.
            assertEquals("https://sync.example.org", store.get(portableText))
        }

    @Test
    fun anAbsentReceiptReadsNullAndAnEmptyOneReadsEmpty() =
        runTest(dispatcher) {
            val store = newStore(scope)

            assertEquals(SettingSnapshot("", null), store.readWithReceipt(portableText))

            // The receipt is opaque and stored verbatim; empty is a real receipt, not `null`.
            store.setWithReceipt(portableText, "v", "")
            assertEquals(SettingSnapshot("v", ""), store.readWithReceipt(portableText))
        }

    @Test
    fun resetWithReceiptRestoresTheDefaultAndKeepsTheReceipt() =
        runTest(dispatcher) {
            val store = newStore(scope)
            store.setWithReceipt(portableText, "v", "r1")

            store.resetWithReceipt(portableText, "r2")

            assertEquals(SettingSnapshot("", "r2"), store.readWithReceipt(portableText))
        }

    @Test
    fun valueAndReceiptSurviveADataStoreReopen() =
        runTest(dispatcher) {
            val firstScope = CoroutineScope(SupervisorJob() + dispatcher)
            newStore(firstScope).setWithReceipt(portableText, "v", "r1")
            firstScope.cancel() // frees the file for a second DataStore on the same path
            testScheduler.runCurrent()

            assertEquals(SettingSnapshot("v", "r1"), newStore(scope).readWithReceipt(portableText))
        }

    @Test
    fun aFailedCommitLeavesThePreviousValueAndReceiptTogether() =
        runTest(dispatcher) {
            val firstScope = CoroutineScope(SupervisorJob() + dispatcher)
            val failing = CommitFailingDataStore(dataStoreFor(firstScope))
            val store = SettingsStore(failing)
            store.setWithReceipt(portableText, "v1", "r1")

            // The mutation runs inside the edit but the commit dies: nothing may half-land.
            failing.failure = IOException("disk full")
            assertFailsWith<IOException> { store.setWithReceipt(portableText, "v2", "r2") }
            assertFailsWith<IOException> { store.set(portableText, "v3") }
            assertEquals(SettingSnapshot("v1", "r1"), store.readWithReceipt(portableText))

            firstScope.cancel()
            testScheduler.runCurrent()
            assertEquals(SettingSnapshot("v1", "r1"), newStore(scope).readWithReceipt(portableText))
        }

    @Test
    fun untrackedWritesClearOnlyTheirOwnReceipt() =
        runTest(dispatcher) {
            val store = newStore(scope)

            store.setWithReceipt(portableLong, 30_000L, "r1")
            store.setWithReceipt(portableText, "v", "t1")
            store.set(portableLong, 40_000L)
            assertEquals(SettingSnapshot(40_000L, null), store.readWithReceipt(portableLong))
            assertEquals(SettingSnapshot("v", "t1"), store.readWithReceipt(portableText))

            store.setWithReceipt(portableLong, 30_000L, "r2")
            store.update(portableLong) { it + 1 }
            assertEquals(SettingSnapshot(30_001L, null), store.readWithReceipt(portableLong))

            store.setWithReceipt(portableLong, 30_000L, "r3")
            store.reset(portableLong)
            assertEquals(SettingSnapshot(10_000L, null), store.readWithReceipt(portableLong))
        }

    @Test
    fun keysOfAnotherFileAreRejectedBeforeAnyMutation() =
        runTest(dispatcher) {
            val store = newStore(scope)
            store.setWithReceipt(portableText, "v", "r1")

            assertFailsWith<IllegalArgumentException> { store.readWithReceipt(deviceText) }
            assertFailsWith<IllegalArgumentException> { store.setWithReceipt(deviceText, "x", "r2") }
            assertFailsWith<IllegalArgumentException> { store.resetWithReceipt(deviceText, "r2") }

            assertEquals(SettingSnapshot("v", "r1"), store.readWithReceipt(portableText))
        }

    @Test
    fun aRejectedValueMutatesNeitherValueNorReceipt() =
        runTest(dispatcher) {
            val store = newStore(scope)
            store.setWithReceipt(restrictedChoice, Panel.FEEDS, "r1")

            assertFailsWith<IllegalArgumentException> {
                store.setWithReceipt(restrictedChoice, Panel.QUEUE, "r2")
            }

            assertEquals(SettingSnapshot(Panel.FEEDS, "r1"), store.readWithReceipt(restrictedChoice))
        }

    @Test
    fun receiptEntriesNeverPoseAsSettings() =
        runTest(dispatcher) {
            val dataStore = dataStoreFor(scope)
            val store = SettingsStore(dataStore)
            store.setWithReceipt(portableText, "v", "r1")

            // Exactly the value entry and its receipt entry — no receipt name is a setting name.
            assertEquals(
                setOf(portableText.name, RECEIPT_NAME_PREFIX + portableText.name),
                dataStore.data
                    .first()
                    .asMap()
                    .keys
                    .map { it.name }
                    .toSet(),
            )
            val registered = AllSettingKeys.list.mapTo(HashSet()) { it.name }
            assertTrue(AllSettingKeys.list.none { it.name.startsWith(RECEIPT_NAME_PREFIX) })
            assertTrue(AllSettingKeys.list.all { (RECEIPT_NAME_PREFIX + it.name) !in registered })
        }

    @Test
    fun readWithReceiptFailsWhereGetReadsDefaults() =
        runTest(dispatcher) {
            val store = SettingsStore(readFailing(IOException("disk full")))

            assertEquals("", store.get(portableText))
            assertFailsWith<IOException> { store.readWithReceipt(portableText) }
        }

    private fun newStore(scope: CoroutineScope): SettingsStore = SettingsStore(dataStoreFor(scope))

    /** A real portable DataStore on the shared temp dir, scoped by [scope]. */
    private fun dataStoreFor(scope: CoroutineScope): DataStore<Preferences> =
        SettingsDataStoreFactory(storagePathsFor(root), scope, dispatcher).create(SettingsFile.PORTABLE)

    /** A store whose every read fails with [failure]; writes are never reached by these tests. */
    private fun readFailing(failure: Throwable): DataStore<Preferences> =
        object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw failure }

            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                error("not used")
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

    /**
     * A [DataStore] that applies the caller's mutation and then fails before the commit — the
     * "write died between mutation and commit" case: the file must keep the previous pair.
     */
    private class CommitFailingDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> {
        var failure: Throwable? = null

        override val data: Flow<Preferences>
            get() = delegate.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            delegate.updateData { current ->
                val updated = transform(current)
                failure?.let { throw it }
                updated
            }
    }
}
