// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.model.ExportLayout
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.core.model.RestoreMode
import ch.lkmc.neutrodyne.core.model.settings.BackupSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The M3 AC6 positive local-transport gate (owner matrix 2026-10-20: API 31/36). Unlike
 * `FirstLaunchRestoreDeviceTest` — which seeds inside the process before `onCreate` — every
 * phase here is armed by `bmgrPhase` (`DeviceTestSelection`/`bmgrArmedMethod`) and driven by
 * `scripts/ci/bmgr-check.sh` around a real `bmgr` LocalTransport cycle on the published
 * release APK. Nothing in this class fakes the transport: the runner's pre-`onCreate` check
 * observes exactly what install-time restore delivered, and the host holds the expectation.
 *
 * - `prepare`: the ordinary cold start (opened.created, band-200 periodic armed), the shared
 *   library fixture through the real graph, a real `SnapshotNowReceiver` publication
 *   (`result=-1`/`ok`, `pending`/`guarded` retried inside its known budget), the post-snapshot
 *   portable value, nonce-named canaries in every excluded domain, and the durable
 *   `bmgrExpected` status record with the real hashes — no marker file, no in-memory map.
 * - `verify`: after a real uninstall/reinstall — the adopted session (NEUTRODYNE_BACKUP,
 *   `fromAndroidBackup`, MERGE HISTORY+UP_NEXT), the restored library, the kept portable
 *   value, the adopted payload hash, retirement, and no canary/DB/device-PB resurrection.
 * - `empty`: a pristine install restores nothing — no restore work, no fixture rows, defaults.
 */
@RunWith(AndroidJUnit4::class)
class BmgrTransportDeviceTest {
    private val app: NeutrodyneApplication
        get() = ApplicationProvider.getApplicationContext()

    private val arguments: Bundle
        get() = InstrumentationRegistry.getArguments()

    private val instrumentation: Instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = PREPARE_TIMEOUT_MS)
    fun prepareLocalTransportBackup() =
        runBlocking {
            requirePhase(BmgrTransportPhases.PREPARE)
            val nonce =
                checkNotNull(arguments.getString(BmgrTransportExpectation.ARG_NONCE)) {
                    "bmgrPhase=prepare requires -e bmgrNonce"
                }
            check(nonce.matches(NONCE)) { "bmgrNonce must be [A-Za-z0-9._-]{8,64}" }
            val graph = app.graph

            // The ordinary first startup: band 100 created the database and band 200 armed
            // the periodic — the periodic existing proves band 110 already evaluated (and
            // skipped) a first-launch adoption on the empty tree.
            val opened = withTimeout(OPEN_TIMEOUT_MS) { graph.databaseOpener.awaitOpen() }
            check(opened.created) { "the prepare phase must start on a created database" }
            awaitUniqueWork(SNAPSHOT_WORK_NAME)

            val db = graph.databaseOpener.requireDatabase()
            for (spec in FirstLaunchRestoreSeed.Fixture.PODCASTS) {
                check(db.podcastDao().byFeedKey(spec.feedKey) == null) {
                    "fixture podcast ${spec.feedKey} must not pre-exist"
                }
            }
            FirstLaunchRestoreSeed.seedLibrary(db)

            setSetting("portable value before snapshot", BackupSettingKeys.OPML_LAYOUT, ExportLayout.GROUPED)
            setSetting("auto-snapshot enable flag", BackupSettingKeys.AUTO_SNAPSHOT_ENABLED, true)

            val receiverLine = publishSnapshotNow()

            // After publication — the restored PB proves SETTINGS is skipped by carrying the
            // value the snapshot's settings.json predates.
            setSetting("portable value after snapshot", BackupSettingKeys.OPML_LAYOUT, ExportLayout.FLAT)

            val installationId = graph.settingsRepository.get(BackupSettingKeys.INSTALLATION_ID)
            check(installationId.isNotEmpty()) { "the receiver write must mint an installation ID" }

            // Leave no debounced write that could interleave with the host's backup window.
            cancelSoonWork()

            val roots = BmgrTransportExpectation.Roots(app)
            val canaries = writeCanaries(roots, nonce)
            // Hashes are measured after every write settled: the snapshot the receiver
            // published and the portable PB carrying the post-snapshot value.
            val snapshot = File(roots.dataDir, BmgrTransportExpectation.SNAPSHOT_REL)
            val portable = File(roots.dataDir, BmgrTransportExpectation.PORTABLE_REL)
            check(snapshot.isFile && snapshot.length() > 0) { "the published snapshot must exist" }
            check(portable.isFile && portable.length() > 0) { "the portable PB must exist" }
            val expected =
                BmgrTransportExpectation.Expected(
                    nonce = nonce,
                    packageName = app.packageName,
                    api = Build.VERSION.SDK_INT,
                    installationId = installationId,
                    snapshotSha256 = FirstLaunchRestoreChecks.sha256Hex(snapshot.readBytes()),
                    snapshotBytes = snapshot.length(),
                    portableSha256 = FirstLaunchRestoreChecks.sha256Hex(portable.readBytes()),
                    portableBytes = portable.length(),
                    portableKey = FirstLaunchRestoreSeed.PORTABLE_KEY_NAME,
                    portableInSnapshot = ExportLayout.GROUPED.name,
                    portableAfter = ExportLayout.FLAT.name,
                    syncIds = FirstLaunchRestoreSeed.Fixture.PODCASTS.associate { it.feedKey to it.syncId },
                    groupUuids = FirstLaunchRestoreSeed.Fixture.GROUPS.associate { it.name to it.uuid },
                    canaries = canaries,
                    receiverLine = receiverLine,
                    preparedAt = System.currentTimeMillis(),
                )

            val status = Bundle()
            status.putString(
                BmgrTransportExpectation.STATUS_EXPECTED,
                BmgrTransportExpectation.encode(expected),
            )
            instrumentation.sendStatus(BmgrTransportExpectation.STATUS_CODE, status)
        }

    @Test(timeout = VERIFY_TIMEOUT_MS)
    fun firstLaunchRestoresTransportedSnapshot() =
        runBlocking {
            requirePhase(BmgrTransportPhases.VERIFY)
            val expected = BmgrTransportExpectation.requireExpected(arguments)
            arguments.getString(BmgrTransportExpectation.ARG_NONCE)?.let {
                check(it == expected.nonce) { "bmgrNonce must match the carried expectation" }
            }
            check(app.packageName == expected.packageName) {
                "the expectation was prepared for ${expected.packageName}, not ${app.packageName}"
            }
            val graph = app.graph
            val roots = BmgrTransportExpectation.Roots(app)

            // The runner already proved the delivered bytes; the app's own start must then be
            // the created-database cold start that triggers the production first-launch restore.
            val opened = withTimeout(OPEN_TIMEOUT_MS) { graph.databaseOpener.awaitOpen() }
            assertTrue("the reinstalled open must report a created database", opened.created)

            val sessionId = RestoredLibraryAsserts.awaitRestoreSucceeded(app)
            val session =
                withTimeout(OPEN_TIMEOUT_MS) {
                    graph.importRepository
                        .observeSession(sessionId)
                        .filterNotNull()
                        .first()
                }
            assertEquals(ImportFormat.NEUTRODYNE_BACKUP, session.format)
            assertTrue(session.options.fromAndroidBackup)
            assertEquals(RestoreMode.MERGE, session.options.restoreMode)
            assertEquals(
                setOf(RestoreCategory.HISTORY, RestoreCategory.UP_NEXT),
                session.options.restoreCategories,
            )
            assertNotNull(session.options.restoreRequestedAt)
            assertNotNull(session.options.committedAt)
            assertTrue(
                "session state must be COMMITTED or a valid later fetch state, was ${session.state}",
                session.state in
                    listOf(ImportState.COMMITTED, ImportState.FETCHING, ImportState.DONE),
            )
            assertEquals(
                "one import_item per archived podcast",
                FirstLaunchRestoreSeed.Fixture.PODCASTS.size,
                session.counts.values.sum(),
            )

            RestoredLibraryAsserts.assertLibraryEqualsFixture(app, expected.syncIds, expected.groupUuids)

            assertEquals(
                "the post-snapshot portable value must survive the restore",
                ExportLayout.FLAT,
                graph.settingsRepository.get(BackupSettingKeys.OPML_LAYOUT),
            )
            val portable = File(roots.dataDir, BmgrTransportExpectation.PORTABLE_REL)
            assertTrue("the transported portable PB must be retained", portable.isFile && portable.length() > 0)
            assertNotEquals(
                "the snapshot's installation ID must not survive — the device store never travels",
                expected.installationId,
                graph.settingsRepository.get(BackupSettingKeys.INSTALLATION_ID),
            )

            val payload = File(app.cacheDir, "import/$sessionId.bin")
            assertTrue("the adopted payload must exist at cache/import/<session>.bin", payload.isFile)
            assertEquals(
                "the adopted payload must hash to the transported snapshot",
                expected.snapshotSha256,
                FirstLaunchRestoreChecks.sha256Hex(payload.readBytes()),
            )

            RestoredLibraryAsserts.assertSnapshotEndState(
                app,
                expected.snapshotSha256,
                expected.installationId,
            )

            assertFalse(
                "the device DataStore must not be restored",
                File(roots.dataDir, BmgrTransportExpectation.DEVICE_PB_REL).exists(),
            )
            for (canary in expected.canaries) {
                check(!roots.resolve(canary).exists()) { "excluded canary was restored: ${canary.where}/${canary.rel}" }
            }
        }

    @Test(timeout = EMPTY_TIMEOUT_MS)
    fun firstLaunchWithoutTransportDataStaysEmpty() =
        runBlocking {
            requirePhase(BmgrTransportPhases.EMPTY)
            val graph = app.graph

            // The runner already proved nothing landed pre-onCreate; the produced start must
            // stay empty — a created database, no restore work, no fixture rows, defaults only.
            val opened = withTimeout(OPEN_TIMEOUT_MS) { graph.databaseOpener.awaitOpen() }
            assertTrue("the empty leg must open a created database", opened.created)

            val workManager = WorkManager.getInstance(app)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RESTORE_ABSENT_MS)
            while (System.nanoTime() < deadline) {
                val infos =
                    FirstLaunchRestoreChecks.awaitRestoreQuery(
                        workManager.getWorkInfosForUniqueWork(RestoredLibraryAsserts.RESTORE_WORK_NAME),
                        deadline - System.nanoTime(),
                    )
                check(infos.isEmpty()) {
                    "no restore work may appear on an empty transport (${infos.joinToString { it.state.name }})"
                }
                delay(POLL_MS)
            }

            val db = graph.databaseOpener.requireDatabase()
            for (spec in FirstLaunchRestoreSeed.Fixture.PODCASTS) {
                assertNull("nothing may reconstruct the fixture", db.podcastDao().byFeedKey(spec.feedKey))
            }
            assertTrue(db.groupDao().groupsInOrder().isEmpty())
            assertTrue(db.queueDao().entries().isEmpty())

            assertEquals(ExportLayout.GROUPED, graph.settingsRepository.get(BackupSettingKeys.OPML_LAYOUT))
            assertEquals("", graph.settingsRepository.get(BackupSettingKeys.INSTALLATION_ID))

            val dataDir = File(app.applicationInfo.dataDir)
            assertFalse(File(dataDir, BmgrTransportExpectation.SNAPSHOT_REL).exists())
            assertFalse(File(dataDir, BmgrTransportExpectation.PORTABLE_REL).exists())
            assertFalse(File(dataDir, BmgrTransportExpectation.DEVICE_PB_REL).exists())
        }

    private fun requirePhase(phase: String) {
        check(BmgrTransportExpectation.phaseOf(arguments) == phase) {
            "${BmgrTransportPhases.TEST_CLASS} requires -e ${BmgrTransportPhases.ARG}=$phase"
        }
        check(bmgrArmedMethod(arguments, Build.VERSION.SDK_INT) == BmgrTransportPhases.METHODS[phase]) {
            "phase $phase must be armed through the exact class#method selector on API 31/36"
        }
    }

    /**
     * The DUMP-protected trigger through the shell, the way `bmgr` test runs do: explicit
     * broadcast, `result=-1` with an `ok at=…` line required. `pending` (the receiver's 8 s
     * budget elapsed while the write continues) and `guarded` (a concurrent write holds the
     * lane) retry inside the bounded budget; every other verdict fails with the raw line.
     */
    private fun publishSnapshotNow(): String {
        var attempt = 0
        while (true) {
            attempt++
            val line =
                shell(
                    "am broadcast --include-stopped-packages" +
                        " -n ${app.packageName}/ch.lkmc.neutrodyne.SnapshotNowReceiver",
                    BROADCAST_TIMEOUT_MS,
                )
            val result = RESULT_LINE.find(line)
            check(result != null) { "unparseable broadcast reply: $line" }
            val code = result.groupValues[1].toInt()
            val data = result.groupValues.getOrElse(2) { "" }
            when {
                code == Activity.RESULT_OK && data.startsWith("ok ") -> {
                    return data
                }

                data == "pending" || data == "guarded" -> {
                    check(attempt < MAX_BROADCAST_ATTEMPTS) {
                        "snapshot publication stayed '$data' after $attempt broadcasts"
                    }
                }

                else -> {
                    check(false) { "snapshot publication failed: $line" }
                }
            }
            Thread.sleep(BROADCAST_RETRY_MS)
        }
    }

    /** `backup-auto-snapshot` exists — band 200 ran, so band 110's adoption check already did. */
    private suspend fun awaitUniqueWork(name: String) {
        val workManager = WorkManager.getInstance(app)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OPEN_TIMEOUT_MS)
        while (true) {
            val infos =
                FirstLaunchRestoreChecks.awaitRestoreQuery(
                    workManager.getWorkInfosForUniqueWork(name),
                    deadline - System.nanoTime(),
                )
            if (infos.isNotEmpty()) return
            check(System.nanoTime() < deadline) { "periodic snapshot work was not armed" }
            delay(POLL_MS)
        }
    }

    private suspend fun cancelSoonWork() {
        val workManager = WorkManager.getInstance(app)
        FirstLaunchRestoreChecks.awaitRestoreQuery(
            workManager.cancelUniqueWork(SOON_WORK_NAME).result,
            TimeUnit.MILLISECONDS.toNanos(OPEN_TIMEOUT_MS),
        )
    }

    /** A real canary per excluded domain — content bytes carry the nonce; nothing writes into a real DB/PB. */
    private fun writeCanaries(
        roots: BmgrTransportExpectation.Roots,
        nonce: String,
    ): List<BmgrTransportExpectation.Canary> {
        val db = File(roots.dataDir, "databases/${NeutrodyneDatabase.FILE_NAME}")
        check(db.isFile) { "the real database file must exist for the canary record" }
        val devicePb = File(roots.dataDir, BmgrTransportExpectation.DEVICE_PB_REL)
        check(devicePb.isFile) { "the device DataStore must exist for the canary record" }

        val canaries =
            mutableListOf(
                BmgrTransportExpectation.Canary("databases", "bmgr-canary-$nonce.db", "", 0),
                BmgrTransportExpectation.Canary("files", "downloads/bmgr-canary-$nonce", "", 0),
                BmgrTransportExpectation.Canary("files", "artwork/bmgr-canary-$nonce", "", 0),
                BmgrTransportExpectation.Canary("files", "other/bmgr-canary-$nonce", "", 0),
                BmgrTransportExpectation.Canary("cache", "bmgr-canary-$nonce", "", 0),
                BmgrTransportExpectation.Canary("no_backup", "bmgr-canary-$nonce", "", 0),
            )
        if (roots.externalPodcastsDir != null) {
            canaries += BmgrTransportExpectation.Canary("external", "bmgr-canary-$nonce", "", 0)
        }
        return canaries.map { canary ->
            val target = roots.resolve(canary)
            target.parentFile?.mkdirs()
            check(target.parentFile?.isDirectory == true) { "cannot create canary dir ${target.parentFile}" }
            val bytes = "bmgr-canary $nonce ${canary.where}/${canary.rel}".toByteArray(Charsets.UTF_8)
            target.writeBytes(bytes)
            canary.copy(sha256 = FirstLaunchRestoreChecks.sha256Hex(bytes), bytes = bytes.size.toLong())
        } +
            listOf(
                BmgrTransportExpectation.Canary(
                    "databases",
                    NeutrodyneDatabase.FILE_NAME,
                    FirstLaunchRestoreChecks.sha256Hex(db.readBytes()),
                    db.length(),
                ),
                BmgrTransportExpectation.Canary(
                    "files",
                    "datastore/device_settings.preferences_pb",
                    FirstLaunchRestoreChecks.sha256Hex(devicePb.readBytes()),
                    devicePb.length(),
                ),
            )
    }

    private suspend fun <T : Any> setSetting(
        what: String,
        key: SettingKey<T>,
        value: T,
    ) {
        check(app.graph.settingsRepository.set(key, value) is Outcome.Success) { "$what must be stored" }
    }

    private fun shell(
        command: String,
        timeoutMs: Long,
    ): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        val future =
            CompletableFuture.supplyAsync {
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }.toString(Charsets.UTF_8)
            }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            pfd.close()
            throw IllegalStateException("shell command timed out: $command", e)
        }
    }

    private companion object {
        /** 05's work table — literals, like the logical test's restore name. */
        const val SNAPSHOT_WORK_NAME = "backup-auto-snapshot"
        const val SOON_WORK_NAME = "backup-auto-snapshot-now"

        val RESULT_LINE = Regex("""result=(-?\d+)(?:,\s*data="([^"]*)")?""")
        val NONCE = Regex("[A-Za-z0-9._-]{8,64}")
        const val OPEN_TIMEOUT_MS = RestoredLibraryAsserts.OPEN_TIMEOUT_MS
        const val POLL_MS = RestoredLibraryAsserts.POLL_MS
        const val BROADCAST_TIMEOUT_MS = 30_000L
        const val BROADCAST_RETRY_MS = 5_000L
        const val MAX_BROADCAST_ATTEMPTS = 5
        const val RESTORE_ABSENT_MS = 20_000L
        const val PREPARE_TIMEOUT_MS = 180_000L
        const val VERIFY_TIMEOUT_MS = 400_000L
        const val EMPTY_TIMEOUT_MS = 120_000L
    }
}
