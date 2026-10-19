// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

/** Admission is AUTO-only. Disk space, partial files and orphans are separate from this budget.
 * W2 calls admitsAuto under claimMutex with its latest rows, before inserting each candidate. */
internal class DownloadQuota(
    private val settings: SettingsRepository,
) {
    private val refused = MutableStateFlow<Long?>(null)
    val refusedEstimate = refused.asStateFlow()
    val capMb = settings.observe(DownloadSettingKeys.STORAGE_CAP_MB)

    suspend fun admitsAuto(
        rows: List<DownloadEntity>,
        estimate: Long?,
    ): Boolean {
        val bytes = estimate ?: UNKNOWN_DOWNLOAD_BYTES
        require(bytes >= 0)
        val allowed = !blocked(rows, capBytes(settings.get(DownloadSettingKeys.STORAGE_CAP_MB)), bytes)
        refused.value = if (allowed) null else bytes
        return allowed
    }

    /** A new planner pass clears a refusal if it no longer has any candidates to admit. */
    fun resetAdmission() {
        refused.value = null
    }

    fun autoBlocked(
        rows: List<DownloadEntity>,
        cap: Long?,
    ): Boolean = blocked(rows, cap, max(refused.value ?: 0, 1))

    private fun blocked(
        rows: List<DownloadEntity>,
        cap: Long?,
        estimate: Long,
    ): Boolean {
        if (cap == null) return false
        var remaining = cap
        for (row in rows) {
            val bytes =
                when {
                    row.state == DownloadState.COMPLETED -> {
                        row.totalBytes ?: 0
                    }

                    row.lane == DownloadLane.AUTO && row.state in RESERVED_STATES -> {
                        max((row.totalBytes ?: row.estimatedBytes ?: UNKNOWN_DOWNLOAD_BYTES) - row.downloadedBytes, 0)
                    }

                    else -> {
                        0
                    }
                }
            if (bytes > remaining) return true
            remaining -= bytes
        }
        return estimate > remaining
    }

    companion object {
        fun capBytes(mb: Int): Long? = mb.takeIf { it > 0 }?.toLong()?.times(BYTES_PER_MB)

        private const val BYTES_PER_MB = 1024L * 1024
        private const val UNKNOWN_DOWNLOAD_BYTES = 150L * BYTES_PER_MB
        private val RESERVED_STATES =
            setOf(DownloadState.QUEUED, DownloadState.RESOLVING, DownloadState.DOWNLOADING, DownloadState.VERIFYING)
    }
}
