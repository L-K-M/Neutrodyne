// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.GraphHolder
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.BackupRepository
import ch.lkmc.neutrodyne.core.model.SnapshotStatus
import ch.lkmc.neutrodyne.core.model.SnapshotWrite
import ch.lkmc.neutrodyne.di.AndroidAppGraph
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 05 Testing with bmgr's shell trigger (`am broadcast -n <pkg>/ch.lkmc.neutrodyne
 * .SnapshotNowReceiver`, DUMP-protected, no intent filter): asks the real snapshot lane
 * to write once and reports the verdict on the ordered-broadcast result line. It is the
 * one named test-hook exception to PLAN N7; every path runs production code.
 *
 * `goAsync()` keeps the broadcast alive while the produce runs on the application scope:
 * the `Deferred` is owned there, so the 8 s budget bounds only the wait; a slow write is
 * never cancelled and answers `pending` instead. `writeSnapshotNow()` reports the
 * before/after status pair it read under the producer's write mutex, so `ok` requires an
 * actual publication and can never be claimed for an interleaved second write. Every path
 * finishes the `PendingResult`.
 */
class SnapshotNowReceiver : BroadcastReceiver() {
    @Inject
    @ApplicationScope
    internal lateinit var appScope: CoroutineScope

    @Inject
    internal lateinit var backupRepository: Lazy<BackupRepository>

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pendingResult = goAsync()
        val write =
            try {
                val graph = (context.applicationContext as GraphHolder).graph as AndroidAppGraph
                graph.inject(this)
                appScope.async { backupRepository.value.writeSnapshotNow() }
            } catch (error: Exception) {
                Log.e(TAG, error) { "snapshot trigger initialization failed" }
                complete(pendingResult, report(Outcome.Failure(BackupError.Io)))
                return
            }
        // Enter the finalizer even if the application scope has already been cancelled.
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var reply = report(Outcome.Failure(BackupError.Io))
            try {
                val outcome =
                    suspendRunCatching { withTimeoutOrNull(AWAIT_MS) { write.await() } }
                        .getOrElse { Outcome.Failure(BackupError.Io) }
                reply = report(outcome)
            } finally {
                complete(pendingResult, reply)
            }
        }
    }

    private fun complete(
        pendingResult: PendingResult,
        reply: Pair<Int, String>,
    ) {
        try {
            pendingResult.setResult(reply.first, reply.second, null)
        } finally {
            pendingResult.finish()
        }
    }

    internal companion object {
        private const val TAG = "SnapshotNowReceiver"

        /** A shell receiver must finish under 10 s even with `goAsync()`; the write is not bounded. */
        const val AWAIT_MS = 8_000L

        const val RESULT_DISABLED = "disabled"
        const val RESULT_GUARDED = "guarded"
        const val RESULT_PENDING = "pending"

        /**
         * The broadcast verdict (05): `null` means the wait budget elapsed while the write
         * keeps running; a failed produce answers `error <tag>`; a successful pair answers
         * `disabled`, `ok at=… bytes=… level=…` only when the publication fields moved, or
         * `guarded` when the lane held.
         */
        fun report(outcome: Outcome<SnapshotWrite, BackupError>?): Pair<Int, String> =
            when (outcome) {
                null -> {
                    Activity.RESULT_CANCELED to RESULT_PENDING
                }

                is Outcome.Failure -> {
                    Activity.RESULT_CANCELED to "error ${tag(outcome.error)}"
                }

                is Outcome.Success -> {
                    val write = outcome.value
                    val after = write.after
                    when {
                        !after.enabled -> Activity.RESULT_CANCELED to RESULT_DISABLED
                        published(write.before, after) -> Activity.RESULT_OK to okLine(after)
                        else -> Activity.RESULT_CANCELED to RESULT_GUARDED
                    }
                }
            }

        private fun published(
            before: SnapshotStatus,
            after: SnapshotStatus,
        ): Boolean =
            after.lastWrittenAt != before.lastWrittenAt ||
                after.bytes != before.bytes ||
                after.level != before.level

        private fun okLine(after: SnapshotStatus): String =
            "ok at=${after.lastWrittenAt} bytes=${after.bytes} level=${after.level}"

        private fun tag(error: BackupError): String =
            when (error) {
                BackupError.NotABackup -> "not_a_backup"
                is BackupError.NewerFormat -> "newer_format"
                is BackupError.Corrupt -> "corrupt"
                BackupError.TooLarge -> "too_large"
                BackupError.Io -> "io"
                BackupError.Storage -> "storage"
                BackupError.RestoreRunning -> "restore_running"
                BackupError.DestinationUnavailable -> "destination_unavailable"
                BackupError.Unsupported -> "unsupported"
            }
    }
}
