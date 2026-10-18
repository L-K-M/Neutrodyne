// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import androidx.work.WorkInfo
import ch.lkmc.neutrodyne.feeds.backup.ArchiveCaps
import ch.lkmc.neutrodyne.feeds.backup.BackupCodec
import ch.lkmc.neutrodyne.feeds.jvm.archive.ZipGuard
import okio.HashingSource
import okio.Path.Companion.toPath
import okio.blackholeSink
import okio.buffer
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Shared only by the device gate and its host regressions. */
internal object FirstLaunchRestoreChecks {
    private const val SESSION_TAG_PREFIX = "restore-session-"
    private const val MAX_SNAPSHOT_SAMPLES = 3
    private val REQUIRED_ENTRIES =
        setOf(
            BackupCodec.ENTRY_LIBRARY,
            BackupCodec.ENTRY_EPISODES,
            BackupCodec.ENTRY_QUEUE,
            BackupCodec.ENTRY_SETTINGS,
        )

    fun assertReplacementSnapshot(
        file: File,
        seededHash: String,
        seededId: String,
        currentId: String,
    ) {
        check(file.length() > 0) { "a replacement snapshot must never be empty" }
        check(sha256Hex(file.readBytes()) != seededHash) { "a replacement snapshot must differ from the seed" }
        val codec = BackupCodec()
        ZipGuard().open(file.path.toPath(), ArchiveCaps.BACKUP).use { reader ->
            val manifest = reader.open(BackupCodec.ENTRY_MANIFEST).use(codec::decodeManifest)
            check(currentId.isNotEmpty() && manifest.installationId == currentId) {
                "a replacement snapshot must carry the current non-empty installation ID"
            }
            check(manifest.installationId != seededId) { "a replacement must belong to the new installation" }

            val declared = manifest.entries.map { it.name }
            check(declared.toSet().size == declared.size) { "duplicate manifest entries" }
            check(declared.containsAll(REQUIRED_ENTRIES) && reader.entryNames.containsAll(REQUIRED_ENTRIES)) {
                "a replacement snapshot must declare and contain every required entry"
            }
            for (entry in manifest.entries) {
                check(entry.name in BackupCodec.ENTRY_NAMES && entry.name != BackupCodec.ENTRY_MANIFEST) {
                    "unexpected manifest entry: ${entry.name}"
                }
                val hashing = HashingSource.sha256(reader.open(entry.name))
                val bytes = hashing.buffer().use { it.readAll(blackholeSink()) }
                check(bytes == entry.bytes && hashing.hash.hex() == entry.sha256) {
                    "replacement snapshot entry ${entry.name} has a size or SHA-256 mismatch"
                }
            }
        }
    }

    fun restoreSessionId(infos: List<WorkInfo>): Long? {
        val failed = infos.firstOrNull { it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED }
        check(failed == null) { "restore work reached ${failed?.state} after ${failed?.runAttemptCount} attempts" }

        val succeeded = infos.firstOrNull { it.state == WorkInfo.State.SUCCEEDED } ?: return null
        val tag =
            checkNotNull(succeeded.tags.firstOrNull { it.startsWith(SESSION_TAG_PREFIX) }) {
                "the restore work must carry its session tag"
            }
        return checkNotNull(tag.removePrefix(SESSION_TAG_PREFIX).toLongOrNull()) {
            "the session tag must carry a numeric id"
        }
    }

    fun <T> awaitRestoreQuery(
        future: Future<T>,
        remainingNanos: Long,
    ): T {
        check(remainingNanos > 0) { "backup-restore exceeded the restore deadline before its query" }
        return try {
            future.get(remainingNanos, TimeUnit.NANOSECONDS)
        } catch (failure: TimeoutException) {
            throw IllegalStateException("backup-restore query exceeded the restore deadline", failure)
        }
    }

    fun runSeedWithinDeadline(
        timeoutMs: Long,
        seed: () -> Unit,
    ) {
        // The runner starts the fixture before JUnit; even blocking IO or scope teardown needs a bound.
        val task = FutureTask { seed() }
        Thread(task, "first-launch-restore-seed").apply { isDaemon = true }.start()
        try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (failure: TimeoutException) {
            throw IllegalStateException("first-launch restore seed did not finish within $timeoutMs ms", failure)
        } catch (failure: ExecutionException) {
            throw checkNotNull(failure.cause)
        } finally {
            if (!task.isDone) task.cancel(true)
        }
    }

    data class RetiredSnapshot(
        val sha256: String,
        val bytes: Long,
    )

    data class SnapshotSample(
        val retired: List<RetiredSnapshot?>,
        val hasReplacement: Boolean,
    )

    enum class SnapshotVerdict { RETIRED, REPLACED, RESAMPLE, MISSING }

    fun readRetiredSnapshot(
        file: File,
        readBytes: () -> ByteArray = file::readBytes,
    ): RetiredSnapshot? {
        val bytes =
            try {
                readBytes()
            } catch (failure: FileNotFoundException) {
                if (!file.exists()) return null
                throw failure
            }
        // Cleanup may unlink this path after the read; hash and size describe the same bytes.
        return RetiredSnapshot(sha256Hex(bytes), bytes.size.toLong())
    }

    fun snapshotVerdict(
        sample: SnapshotSample,
        seededHash: String,
    ): SnapshotVerdict {
        if (sample.retired.any { it == null }) return SnapshotVerdict.RESAMPLE
        val retired = sample.retired.filterNotNull()
        if (retired.any { it.sha256 == seededHash }) {
            check(retired.all { it.bytes > 0 }) { "retired snapshots must never be empty" }
            return SnapshotVerdict.RETIRED
        }
        if (sample.hasReplacement) return SnapshotVerdict.REPLACED
        return SnapshotVerdict.MISSING
    }

    suspend fun assertSnapshotEndState(
        seededHash: String,
        sample: () -> SnapshotSample,
        validateReplacement: suspend () -> Unit,
    ) {
        repeat(MAX_SNAPSHOT_SAMPLES) {
            val sampled = sample()
            when (snapshotVerdict(sampled, seededHash)) {
                SnapshotVerdict.RESAMPLE -> return@repeat
                SnapshotVerdict.MISSING -> error("the original snapshot is neither retired nor replaced")
                SnapshotVerdict.RETIRED -> if (sampled.hasReplacement) validateReplacement()
                SnapshotVerdict.REPLACED -> validateReplacement()
            }
            return
        }
        error("retired snapshots kept disappearing after $MAX_SNAPSHOT_SAMPLES resamples")
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
