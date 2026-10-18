// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.app.Application
import androidx.work.WorkInfo
import ch.lkmc.neutrodyne.FirstLaunchRestoreChecks.RetiredSnapshot
import ch.lkmc.neutrodyne.FirstLaunchRestoreChecks.SnapshotSample
import ch.lkmc.neutrodyne.FirstLaunchRestoreChecks.SnapshotVerdict
import ch.lkmc.neutrodyne.feeds.backup.AppInfoV1
import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import ch.lkmc.neutrodyne.feeds.backup.BackupKind
import ch.lkmc.neutrodyne.feeds.backup.BackupManifest
import ch.lkmc.neutrodyne.feeds.backup.CountsV1
import ch.lkmc.neutrodyne.feeds.backup.EntryInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26])
class FirstLaunchRestoreChecksTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun replacementRejectsManifestOnlyZipWithEmptyId() {
        val file = archive(mapOf(BackupCodec.ENTRY_MANIFEST to """{"installationId":""}"""))
        assertThrows("manifest-only ZIP must be rejected", Exception::class.java) { validate(file) }
    }

    @Test
    fun replacementRejectsMissingRequiredPayload() {
        val file = archive(archiveEntries() - BackupCodec.ENTRY_QUEUE)
        assertThrows("missing queue payload must be rejected", Exception::class.java) { validate(file) }
    }

    @Test
    fun replacementRejectsUndeclaredRequiredPayload() {
        val file = archive(archiveEntries(declared = payloads - BackupCodec.ENTRY_QUEUE))
        assertThrows("undeclared queue payload must be rejected", Exception::class.java) { validate(file) }
    }

    @Test
    fun replacementRejectsPayloadHashMismatch() {
        val corruptLibrary = """{"podcasts":{},"groups":[]}"""
        val file = archive(archiveEntries() + (BackupCodec.ENTRY_LIBRARY to corruptLibrary))
        assertThrows("payload hash mismatch must be rejected", Exception::class.java) { validate(file) }
    }

    @Test
    fun replacementRejectsPayloadByteCountMismatch() {
        val entries =
            payloads.map { (name, text) ->
                entry(name, text).copy(bytes = text.toByteArray().size + 1L)
            }
        val manifestJson = codec.encodeManifest(manifest(entries = entries))
        val file = archive(payloads + (BackupCodec.ENTRY_MANIFEST to manifestJson))
        assertThrows("payload byte count mismatch must be rejected", Exception::class.java) { validate(file) }
    }

    @Test
    fun replacementRejectsEmptyInstallationId() {
        val file = archive(archiveEntries(id = ""))
        assertThrows("empty installation ID must be rejected", Exception::class.java) { validate(file) }
    }

    @Test
    fun replacementRejectsForeignInstallationId() {
        val file = archive(archiveEntries(id = "third-installation"))
        assertThrows("replacement must match current ID", Exception::class.java) { validate(file) }
    }

    @Test
    fun replacementAcceptsCompleteArchiveFromCurrentInstallation() = validate(archive(archiveEntries()))

    @Test
    fun replacementRejectsUnchangedSeedArchive() {
        val file = archive(archiveEntries())
        assertThrows(IllegalStateException::class.java) {
            FirstLaunchRestoreChecks.assertReplacementSnapshot(
                file,
                FirstLaunchRestoreChecks.sha256Hex(file.readBytes()),
                "seed",
                "current",
            )
        }
    }

    @Test
    fun succeededThenFailedWorkIsRejected() =
        terminalWorkIsRejected(WorkInfo.State.FAILED, order = WorkOrder.SUCCESS_FIRST)

    @Test
    fun failedThenSucceededWorkIsRejected() =
        terminalWorkIsRejected(WorkInfo.State.FAILED, order = WorkOrder.TERMINAL_FIRST)

    @Test
    fun succeededThenCancelledWorkIsRejected() =
        terminalWorkIsRejected(WorkInfo.State.CANCELLED, order = WorkOrder.SUCCESS_FIRST)

    @Test
    fun cancelledThenSucceededWorkIsRejected() =
        terminalWorkIsRejected(WorkInfo.State.CANCELLED, order = WorkOrder.TERMINAL_FIRST)

    @Test
    fun successfulWorkReturnsItsSessionId() {
        assertEquals(42L, FirstLaunchRestoreChecks.restoreSessionId(listOf(work(WorkInfo.State.SUCCEEDED))))
    }

    @Test
    fun unfinishedWorkKeepsPolling() {
        assertNull(FirstLaunchRestoreChecks.restoreSessionId(listOf(work(WorkInfo.State.ENQUEUED))))
        assertNull(FirstLaunchRestoreChecks.restoreSessionId(emptyList()))
    }

    @Test
    fun seedDeadlineBoundsBlockingFixtureWork() {
        val failure =
            assertThrows("seed must have its own deadline", IllegalStateException::class.java) {
                FirstLaunchRestoreChecks.runSeedWithinDeadline(20) {
                    CountDownLatch(1).await(200, TimeUnit.MILLISECONDS)
                }
            }
        assertTrue(failure.message.orEmpty().contains("seed"))
        assertTrue(failure.message.orEmpty().contains("20 ms"))
    }

    @Test
    fun seedDeadlineBoundsScopeJoin() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { delay(200) }
            }
        }
        try {
            assertThrows("scope join must respect the seed deadline", IllegalStateException::class.java) {
                FirstLaunchRestoreChecks.runSeedWithinDeadline(20) {
                    runBlocking { scope.coroutineContext.job.cancelAndJoin() }
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun seedDeadlinePreservesFixtureFailure() {
        val original = IllegalArgumentException("snapshot write failed")
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                FirstLaunchRestoreChecks.runSeedWithinDeadline(1_000) { throw original }
            }
        assertEquals(original, failure)
    }

    @Test
    fun restoreQueryUsesRemainingBudget() {
        val future =
            object : CompletableFuture<String>() {
                override fun get(): String = throw AssertionError("unbounded Future.get() bypassed restore deadline")

                override fun get(
                    timeout: Long,
                    unit: TimeUnit,
                ): String {
                    assertEquals(123L, timeout)
                    assertEquals(TimeUnit.NANOSECONDS, unit)
                    return "bounded"
                }
            }
        assertEquals("bounded", FirstLaunchRestoreChecks.awaitRestoreQuery(future, 123))

        val failure =
            assertThrows(IllegalStateException::class.java) {
                FirstLaunchRestoreChecks.awaitRestoreQuery(
                    CompletableFuture<String>(),
                    TimeUnit.MILLISECONDS.toNanos(20),
                )
            }
        assertTrue(failure.cause is TimeoutException)
    }

    @Test
    fun restoreQueryRejectsExpiredBudget() {
        val future = CompletableFuture.completedFuture("late success")
        assertThrows("expired restore budget must fail", IllegalStateException::class.java) {
            FirstLaunchRestoreChecks.awaitRestoreQuery(future, 0)
        }
    }

    @Test
    fun retiredFileDisappearingBeforeHashRequestsResample() {
        val file = temporary.newFile("restored-1.zip")
        assertTrue(file.delete())
        assertNull(FirstLaunchRestoreChecks.readRetiredSnapshot(file))
        assertEquals(
            SnapshotVerdict.RESAMPLE,
            FirstLaunchRestoreChecks.snapshotVerdict(SnapshotSample(listOf(null), true), "seed-hash"),
        )
    }

    @Test
    fun retiredFileDeletedAfterReadKeepsItsObservedLength() {
        val file = temporary.newFile("restored-2.zip").apply { writeText("seed") }
        val observed =
            FirstLaunchRestoreChecks.readRetiredSnapshot(file) {
                file.readBytes().also { assertTrue(file.delete()) }
            }
        assertEquals("read bytes must determine length after cleanup", 4L, observed?.bytes)
    }

    @Test
    fun disappearedRetiredFileResamplesAndValidatesReplacement() =
        runBlocking<Unit> {
            var samples = 0
            var validated = false
            FirstLaunchRestoreChecks.assertSnapshotEndState(
                "seed-hash",
                sample = {
                    samples++
                    SnapshotSample(if (samples == 1) listOf(null) else emptyList(), true)
                },
                validateReplacement = { validated = true },
            )
            assertEquals(2, samples)
            assertTrue(validated)
        }

    @Test
    fun repeatedRetiredFileDisappearanceHasBoundedResampling() {
        var samples = 0
        val failure =
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    FirstLaunchRestoreChecks.assertSnapshotEndState(
                        "seed-hash",
                        sample = {
                            samples++
                            SnapshotSample(listOf(null), true)
                        },
                        validateReplacement = { error("unstable sample cannot pass") },
                    )
                }
            }
        assertTrue("must resample before giving up", samples > 1)
        assertTrue("resampling must be bounded", samples <= 5)
        assertTrue(failure.message.orEmpty().contains("resampl"))
    }

    @Test
    fun resamplingDoesNotHideInvalidReplacement() {
        var samples = 0
        val invalid = IllegalStateException("invalid replacement")
        val failure =
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    FirstLaunchRestoreChecks.assertSnapshotEndState(
                        "seed-hash",
                        sample = {
                            samples++
                            SnapshotSample(if (samples == 1) listOf(null) else emptyList(), true)
                        },
                        validateReplacement = { throw invalid },
                    )
                }
            }
        assertEquals(invalid, failure)
    }

    @Test
    fun retiredSeedWithoutReplacementIsAccepted() =
        runBlocking<Unit> {
            FirstLaunchRestoreChecks.assertSnapshotEndState(
                "seed-hash",
                sample = { SnapshotSample(listOf(RetiredSnapshot("seed-hash", 4)), false) },
                validateReplacement = { error("no replacement exists") },
            )
        }

    @Test
    fun emptySurvivingRetiredSnapshotIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            FirstLaunchRestoreChecks.snapshotVerdict(
                SnapshotSample(listOf(RetiredSnapshot("seed-hash", 0)), true),
                "seed-hash",
            )
        }
    }

    @Test
    fun missingRetirementAndReplacementIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                FirstLaunchRestoreChecks.assertSnapshotEndState(
                    "seed-hash",
                    sample = { SnapshotSample(emptyList(), false) },
                    validateReplacement = {},
                )
            }
        }
    }

    private enum class WorkOrder { SUCCESS_FIRST, TERMINAL_FIRST }

    private fun terminalWorkIsRejected(
        terminal: WorkInfo.State,
        order: WorkOrder,
    ) {
        val succeeded = work(WorkInfo.State.SUCCEEDED)
        val failed = work(terminal)
        val infos = if (order == WorkOrder.TERMINAL_FIRST) listOf(failed, succeeded) else listOf(succeeded, failed)
        val failure =
            assertThrows("$terminal must win over SUCCEEDED", IllegalStateException::class.java) {
                FirstLaunchRestoreChecks.restoreSessionId(infos)
            }
        assertTrue(failure.message.orEmpty().contains(terminal.name))
    }

    private fun work(state: WorkInfo.State): WorkInfo =
        WorkInfo(id = UUID.randomUUID(), state = state, tags = setOf("restore-session-42"))

    private fun validate(file: File) =
        FirstLaunchRestoreChecks.assertReplacementSnapshot(file, "seed-hash", "seed", "current")

    private fun archiveEntries(
        id: String = "current",
        declared: Map<String, String> = payloads,
    ): Map<String, String> =
        payloads + (
            BackupCodec.ENTRY_MANIFEST to
                codec.encodeManifest(
                    manifest(id, declared.map { (name, text) -> entry(name, text) }),
                )
        )

    private fun manifest(
        id: String = "current",
        entries: List<EntryInfo>,
    ): BackupManifest =
        BackupManifest(
            createdAt = "2026-10-18T00:00:00Z",
            kind = BackupKind.AUTO_SNAPSHOT,
            app = AppInfoV1("test", 1, "x86_64", "android"),
            dbSchemaVersion = 1,
            episodeKeysVersion = 1,
            urlNormalizerVersion = 1,
            installationId = id,
            counts = CountsV1(0, 0, 0, 0, 0, 0, 0),
            entries = entries,
        )

    private fun entry(
        name: String,
        text: String,
    ): EntryInfo {
        val bytes = text.toByteArray()
        return EntryInfo(name, bytes.size.toLong(), FirstLaunchRestoreChecks.sha256Hex(bytes), 0)
    }

    private fun archive(entries: Map<String, String>): File {
        val file = temporary.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private val codec = BackupCodec()
    private val payloads =
        mapOf(
            BackupCodec.ENTRY_LIBRARY to """{"podcasts":[],"groups":[]}""",
            BackupCodec.ENTRY_EPISODES to "",
            BackupCodec.ENTRY_QUEUE to """{"upNext":[]}""",
            BackupCodec.ENTRY_SETTINGS to """{"values":[]}""",
        )
}
